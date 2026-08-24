package grandlineduo.game.quest

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object QuestFieldLanIntegrationTest {
    fun register() {
        test("P2 field quests reconnect after failure converge and reward once over real TCP") {
            val explore = fieldQuest(
                id = "field-lan-explore-main",
                type = QuestType.EXPLORE,
                reward = QuestReward(berries = 4_000),
            )
            val exploreSibling = fieldQuest(
                id = "field-lan-explore-sibling",
                type = QuestType.EXPLORE,
                reward = QuestReward(berries = 6_000),
            )
            val collect = fieldQuest(
                id = "field-lan-collect-main",
                type = QuestType.COLLECT,
                reward = QuestReward(berries = 5_000),
            )
            val collectSibling = fieldQuest(
                id = "field-lan-collect-sibling",
                type = QuestType.COLLECT,
                reward = QuestReward(berries = 7_000),
            )
            val initial = fieldWorld(
                id = "quest-field-lan",
                explore = explore,
                exploreSibling = exploreSibling,
                collect = collect,
                collectSibling = collectSibling,
            )
            val seed = findCampaignSeed(initial, explore, collect)
            val hostDir = Files.createTempDirectory("gld-field-lan-host")
            val clientDir = Files.createTempDirectory("gld-field-lan-client")
            val hostStore = SnapshotStore(hostDir)
            val clientStore = SnapshotStore(clientDir)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = seed, snapshotStore = hostStore)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()

                val firstReplica = ClientReplica(initial)
                val firstCommand = GameplayWireCommand.QuestAction(
                    "field-lan-explore-first",
                    "p2",
                    "EXPLORE_SITE",
                    explore.questId,
                    1,
                )
                var firstOutcome = false
                var secondOutcome = false

                LanClientConnection("127.0.0.1", server.boundPort, "p2", firstReplica, clientStore).use { firstClient ->
                    firstClient.connect()
                    firstClient.sendGameplay(firstCommand)

                    val firstLast = QuestFieldState.readLast(host.state, explore.questId)!!
                    firstOutcome = firstLast.success
                    assertEquals(1, QuestFieldState.attemptCount(host.state, explore.questId))
                    assertEquals(39, host.state.players.getValue("p2").energy)
                    assertEquals(40, host.state.players.getValue("p1").energy)
                    assertEquals(host.state, firstReplica.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(firstReplica.state))
                    assertEquals(host.state, clientStore.loadLatestValid())

                    firstClient.disconnect()

                    handler.handle(
                        GameplayWireCommand.QuestAction(
                            "field-lan-explore-host-second",
                            "p1",
                            "EXPLORE_SITE",
                            explore.questId,
                            1,
                        ),
                        60_002L,
                    )
                    val secondLast = QuestFieldState.readLast(host.state, explore.questId)!!
                    secondOutcome = secondLast.success
                    assertEquals("p1", secondLast.actorId)
                    assertEquals(2, QuestFieldState.attemptCount(host.state, explore.questId))
                    assertEquals(39, host.state.players.getValue("p1").energy)
                    assertEquals(39, host.state.players.getValue("p2").energy)
                }

                val staleClientState = clientStore.loadLatestValid()!!
                assertEquals(1, QuestFieldState.attemptCount(staleClientState, explore.questId))
                assertEquals(40, staleClientState.players.getValue("p1").energy)
                val reconnectedReplica = ClientReplica(staleClientState)

                LanClientConnection("127.0.0.1", server.boundPort, "p2", reconnectedReplica, clientStore).use { client ->
                    client.connect()

                    assertEquals(host.state, reconnectedReplica.state)
                    assertEquals(39, reconnectedReplica.state.players.getValue("p1").energy)
                    assertEquals(39, reconnectedReplica.state.players.getValue("p2").energy)
                    assertEquals(2, QuestFieldState.attemptCount(reconnectedReplica.state, explore.questId))
                    assertEquals(
                        QuestFieldState.readLast(host.state, explore.questId),
                        QuestFieldState.readLast(reconnectedReplica.state, explore.questId),
                    )
                    assertEquals(
                        host.state.questBoard.active.getValue(explore.questId).progress,
                        reconnectedReplica.state.questBoard.active.getValue(explore.questId).progress,
                    )
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(reconnectedReplica.state))

                    val exploreOutcomes = attemptUntilReady(
                        prefix = "field-lan-explore-resume",
                        action = "EXPLORE_SITE",
                        questId = explore.questId,
                        host = host,
                        client = client,
                        clientReplica = reconnectedReplica,
                    )
                    assertEquals(0, host.state.questBoard.active.getValue(exploreSibling.questId).progress)

                    val collectOutcomes = attemptUntilReady(
                        prefix = "field-lan-collect",
                        action = "SEARCH_SUPPLIES",
                        questId = collect.questId,
                        host = host,
                        client = client,
                        clientReplica = reconnectedReplica,
                    )
                    assertEquals(0, host.state.questBoard.active.getValue(collectSibling.questId).progress)

                    val allOutcomes = listOf(firstOutcome, secondOutcome) + exploreOutcomes + collectOutcomes
                    assertTrue(allOutcomes.any { !it }, "Seeded TCP field sequence must exercise at least one failed d20 check")
                    assertEquals(QuestStatus.READY_TO_TURN_IN, host.state.questBoard.active.getValue(explore.questId).status)
                    assertEquals(QuestStatus.READY_TO_TURN_IN, host.state.questBoard.active.getValue(collect.questId).status)

                    val collectBeforeShop = host.state.questBoard.active.getValue(collect.questId).progress
                    client.sendGameplay(
                        GameplayWireCommand.WorldAction(
                            "field-lan-shop-bandage",
                            "p2",
                            "SHOP_BUY",
                            "bandage",
                            1,
                        )
                    )
                    assertEquals(collectBeforeShop, host.state.questBoard.active.getValue(collect.questId).progress)
                    assertEquals(host.state, reconnectedReplica.state)

                    val berriesBeforeTurnIn = host.state.partyBerries
                    val exploreTurnIn = GameplayWireCommand.QuestAction(
                        "field-lan-explore-turn-in",
                        "p2",
                        "TURN_IN",
                        explore.questId,
                    )
                    client.sendGameplay(exploreTurnIn)
                    val berriesAfterExplore = host.state.partyBerries
                    assertEquals(berriesBeforeTurnIn + explore.reward.berries, berriesAfterExplore)
                    assertTrue(explore.questId in host.state.questBoard.completedQuestIds)
                    client.sendGameplay(exploreTurnIn)
                    assertEquals(berriesAfterExplore, host.state.partyBerries)

                    val collectTurnIn = GameplayWireCommand.QuestAction(
                        "field-lan-collect-turn-in",
                        "p2",
                        "TURN_IN",
                        collect.questId,
                    )
                    client.sendGameplay(collectTurnIn)
                    val berriesAfterCollect = host.state.partyBerries
                    assertEquals(berriesAfterExplore + collect.reward.berries, berriesAfterCollect)
                    assertTrue(collect.questId in host.state.questBoard.completedQuestIds)
                    client.sendGameplay(collectTurnIn)
                    assertEquals(berriesAfterCollect, host.state.partyBerries)

                    val stateBeforeOldRetry = host.state
                    val p2EnergyBeforeOldRetry = stateBeforeOldRetry.players.getValue("p2").energy
                    val attemptsBeforeOldRetry = QuestFieldState.attemptCount(stateBeforeOldRetry, explore.questId)
                    client.sendGameplay(firstCommand)
                    assertEquals(p2EnergyBeforeOldRetry, host.state.players.getValue("p2").energy)
                    assertEquals(attemptsBeforeOldRetry, QuestFieldState.attemptCount(host.state, explore.questId))
                    assertEquals(stateBeforeOldRetry.questBoard, host.state.questBoard)

                    assertEquals(0, host.state.questBoard.active.getValue(exploreSibling.questId).progress)
                    assertEquals(0, host.state.questBoard.active.getValue(collectSibling.questId).progress)
                    assertEquals(host.state, reconnectedReplica.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(reconnectedReplica.state))
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(reconnectedReplica.state, clientStore.loadLatestValid())
                }
            }
        }
    }

    private fun attemptUntilReady(
        prefix: String,
        action: String,
        questId: String,
        host: HostReplica,
        client: LanClientConnection,
        clientReplica: ClientReplica,
        maxAttempts: Int = 30,
    ): List<Boolean> {
        val outcomes = mutableListOf<Boolean>()
        var step = 1
        while (host.state.questBoard.active.getValue(questId).status == QuestStatus.ACTIVE && step <= maxAttempts) {
            client.sendGameplay(
                GameplayWireCommand.QuestAction(
                    "$prefix-$step",
                    "p2",
                    action,
                    questId,
                    1,
                )
            )
            outcomes += QuestFieldState.readLast(host.state, questId)!!.success
            assertEquals(host.state, clientReplica.state)
            assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))
            step++
        }
        assertEquals(QuestStatus.READY_TO_TURN_IN, host.state.questBoard.active.getValue(questId).status)
        return outcomes
    }

    private data class SimulatedSequence(
        val outcomes: List<Boolean>,
        val completed: Boolean,
    )

    private fun findCampaignSeed(
        world: WorldState,
        explore: QuestDefinition,
        collect: QuestDefinition,
    ): Long {
        val exploreProgress = world.questBoard.active.getValue(explore.questId)
        val collectProgress = world.questBoard.active.getValue(collect.questId)
        for (seed in 1L..10_000L) {
            val exploreSequence = simulateSequence(
                world = world,
                progress = exploreProgress,
                actionType = QuestFieldActionType.EXPLORE_SITE,
                campaignSeed = seed,
                actorForOrdinal = { ordinal -> if (ordinal == 2) "p1" else "p2" },
            )
            val collectSequence = simulateSequence(
                world = world,
                progress = collectProgress,
                actionType = QuestFieldActionType.SEARCH_SUPPLIES,
                campaignSeed = seed,
                actorForOrdinal = { "p2" },
            )
            val outcomes = exploreSequence.outcomes + collectSequence.outcomes
            if (
                exploreSequence.completed &&
                collectSequence.completed &&
                outcomes.any { !it } &&
                outcomes.size <= 35
            ) {
                return seed
            }
        }
        error("No bounded deterministic field seed found")
    }

    private fun simulateSequence(
        world: WorldState,
        progress: QuestProgress,
        actionType: QuestFieldActionType,
        campaignSeed: Long,
        actorForOrdinal: (Int) -> String,
        maxAttempts: Int = 30,
    ): SimulatedSequence {
        val outcomes = mutableListOf<Boolean>()
        var objectiveProgress = 0
        var ordinal = 1
        while (objectiveProgress < progress.definition.requiredAmount && ordinal <= maxAttempts) {
            val result = QuestFieldResolver.resolve(
                world = world,
                progress = progress,
                actorId = actorForOrdinal(ordinal),
                actionType = actionType,
                attemptOrdinal = ordinal,
                campaignSeed = campaignSeed,
            )
            outcomes += result.success
            if (result.success) {
                objectiveProgress = (objectiveProgress + result.progressAmount)
                    .coerceAtMost(progress.definition.requiredAmount)
            }
            ordinal++
        }
        return SimulatedSequence(outcomes, objectiveProgress >= progress.definition.requiredAmount)
    }

    private fun fieldWorld(
        id: String,
        explore: QuestDefinition,
        exploreSibling: QuestDefinition,
        collect: QuestDefinition,
        collectSibling: QuestDefinition,
    ): WorldState {
        val p1Profile = profile("Kairo")
        val p2Profile = profile("Namiya")
        val base = WorldState(
            campaignId = id,
            islandId = "stormglass-cay",
            partyBerries = 20_000,
            players = mapOf(
                "p1" to PlayerState(
                    "p1",
                    p1Profile.name,
                    p1Profile.maxHp - 5,
                    p1Profile.maxHp,
                    18_000_000L,
                    40,
                    40,
                    p1Profile,
                ),
                "p2" to PlayerState(
                    "p2",
                    p2Profile.name,
                    p2Profile.maxHp - 7,
                    p2Profile.maxHp,
                    16_000_000L,
                    40,
                    40,
                    p2Profile,
                ),
            ),
            questBoard = QuestBoardState(
                active = mapOf(
                    explore.questId to QuestProgress(explore, QuestStatus.ACTIVE, 0, "p1"),
                    exploreSibling.questId to QuestProgress(exploreSibling, QuestStatus.ACTIVE, 0, "p2"),
                    collect.questId to QuestProgress(collect, QuestStatus.ACTIVE, 0, "p2"),
                    collectSibling.questId to QuestProgress(collectSibling, QuestStatus.ACTIVE, 0, "p1"),
                ),
            ),
            worldFlags = mapOf(
                "campaign.mode" to "HOST_COOP",
                "campaign.chapter" to "0",
                "HAS_LOG_POSE" to "1",
            ),
        )
        return StormglassPersistenceAdapter.encode(base, ScenarioState(stage = ScenarioStage.COMPLETE), null)
    }

    private fun profile(name: String) =
        (CharacterCreation.create(CharacterCreationTest.validDraft().copy(name = name)) as CharacterCreationResult.Success).profile

    private fun fieldQuest(id: String, type: QuestType, reward: QuestReward) = QuestDefinition(
        questId = id,
        islandId = "stormglass-cay",
        title = if (type == QuestType.EXPLORE) "Explorar ruínas esquecidas" else "Buscar suprimentos médicos",
        type = type,
        rarity = QuestRarity.RARE,
        issuerFaction = "CIVILIANS",
        targetId = if (type == QuestType.EXPLORE) "forgotten-ruins" else "medical-supplies",
        requiredAmount = if (type == QuestType.EXPLORE) 6 else 8,
        reward = reward,
    )
}
