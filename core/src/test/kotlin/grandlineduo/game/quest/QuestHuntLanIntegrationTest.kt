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
import grandlineduo.game.InventoryEngine
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object QuestHuntLanIntegrationTest {
    fun register() {
        test("P2 hunt reconnects mid encounter converges across three fights and rewards once over real TCP") {
            val hunt = huntQuest("hunt-lan-main", reward = QuestReward(berries = 6_000))
            val sibling = huntQuest("hunt-lan-sibling", reward = QuestReward(berries = 8_000))
            val initial = huntWorld("quest-hunt-lan", hunt, sibling)
            val hostDir = Files.createTempDirectory("gld-hunt-lan-host")
            val clientDir = Files.createTempDirectory("gld-hunt-lan-client")
            val hostStore = SnapshotStore(hostDir)
            val clientStore = SnapshotStore(clientDir)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 8441L, snapshotStore = hostStore)

            val berriesBefore = initial.partyBerries
            val bountiesBefore = initial.players.mapValues { it.value.bounty }
            val p1InventoryBefore = InventoryEngine.read(initial, "p1")
            val p2InventoryBefore = InventoryEngine.read(initial, "p2")
            val flagsBefore = initial.worldFlags

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()

                val firstReplica = ClientReplica(initial)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", firstReplica, clientStore).use { firstClient ->
                    firstClient.connect()
                    firstClient.sendGameplay(
                        GameplayWireCommand.QuestAction("hunt-lan-start-1", "p2", "START_HUNT", hunt.questId)
                    )

                    val started = host.state.activeCombat!!
                    val enemyId = started.enemy.id
                    assertEquals(hunt.questId, host.state.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG])
                    assertEquals(1, started.round)
                    assertEquals(host.state, firstReplica.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(firstReplica.state))
                    assertEquals(host.state, clientStore.loadLatestValid())

                    firstClient.disconnect()

                    handler.handle(
                        GameplayWireCommand.CombatAction("hunt-lan-e1-r1-p1", "p1", CombatActionType.SETUP.name),
                        50_001L,
                    )
                    assertEquals(enemyId, host.state.activeCombat!!.enemy.id)
                    assertEquals(
                        CombatActionType.SETUP,
                        host.state.activeCombat!!.lockedActions.getValue("p1").type,
                    )
                }

                val staleClientState = clientStore.loadLatestValid()!!
                assertTrue("p1" !in staleClientState.activeCombat!!.lockedActions)
                val reconnectedReplica = ClientReplica(staleClientState)

                LanClientConnection("127.0.0.1", server.boundPort, "p2", reconnectedReplica, clientStore).use { client ->
                    client.connect()

                    assertEquals(host.state, reconnectedReplica.state)
                    assertEquals(hunt.questId, reconnectedReplica.state.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG])
                    assertEquals(host.state.activeCombat!!.enemy.id, reconnectedReplica.state.activeCombat!!.enemy.id)
                    assertEquals(host.state.activeCombat!!.round, reconnectedReplica.state.activeCombat!!.round)
                    assertEquals(
                        CombatActionType.SETUP,
                        reconnectedReplica.state.activeCombat!!.lockedActions.getValue("p1").type,
                    )
                    assertEquals(
                        host.state.activeCombat!!.players.mapValues { it.value.hp },
                        reconnectedReplica.state.activeCombat!!.players.mapValues { it.value.hp },
                    )
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(reconnectedReplica.state))

                    client.sendGameplay(
                        GameplayWireCommand.CombatAction("hunt-lan-e1-r1-p2", "p2", CombatActionType.FINISHER.name)
                    )
                    finishEncounter(
                        encounter = 1,
                        host = host,
                        handler = handler,
                        client = client,
                        clientReplica = reconnectedReplica,
                    )
                    assertEncounterOutcome(host.state, hunt, sibling, expectedProgress = 1, expectedStatus = QuestStatus.ACTIVE)
                    assertNoEarlyReward(
                        host.state,
                        berriesBefore,
                        bountiesBefore,
                        p1InventoryBefore,
                        p2InventoryBefore,
                        flagsBefore,
                    )

                    client.sendGameplay(
                        GameplayWireCommand.QuestAction("hunt-lan-start-2", "p2", "START_HUNT", hunt.questId)
                    )
                    assertEquals("${hunt.targetId}-hunt-2", host.state.activeCombat!!.enemy.id)
                    finishEncounter(
                        encounter = 2,
                        host = host,
                        handler = handler,
                        client = client,
                        clientReplica = reconnectedReplica,
                    )
                    assertEncounterOutcome(host.state, hunt, sibling, expectedProgress = 2, expectedStatus = QuestStatus.ACTIVE)
                    assertNoEarlyReward(
                        host.state,
                        berriesBefore,
                        bountiesBefore,
                        p1InventoryBefore,
                        p2InventoryBefore,
                        flagsBefore,
                    )

                    client.sendGameplay(
                        GameplayWireCommand.QuestAction("hunt-lan-start-3", "p2", "START_HUNT", hunt.questId)
                    )
                    assertEquals("${hunt.targetId}-hunt-3", host.state.activeCombat!!.enemy.id)
                    finishEncounter(
                        encounter = 3,
                        host = host,
                        handler = handler,
                        client = client,
                        clientReplica = reconnectedReplica,
                    )
                    assertEncounterOutcome(
                        host.state,
                        hunt,
                        sibling,
                        expectedProgress = 3,
                        expectedStatus = QuestStatus.READY_TO_TURN_IN,
                    )
                    assertNoEarlyReward(
                        host.state,
                        berriesBefore,
                        bountiesBefore,
                        p1InventoryBefore,
                        p2InventoryBefore,
                        flagsBefore,
                    )

                    val turnIn = GameplayWireCommand.QuestAction(
                        "hunt-lan-turn-in",
                        "p2",
                        "TURN_IN",
                        hunt.questId,
                    )
                    client.sendGameplay(turnIn)
                    val berriesAfterTurnIn = host.state.partyBerries
                    assertEquals(berriesBefore + hunt.reward.berries, berriesAfterTurnIn)
                    assertTrue(hunt.questId in host.state.questBoard.completedQuestIds)
                    assertEquals(0, host.state.questBoard.active.getValue(sibling.questId).progress)

                    client.sendGameplay(turnIn)
                    assertEquals(berriesAfterTurnIn, host.state.partyBerries)
                    assertEquals(host.state, reconnectedReplica.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(reconnectedReplica.state))
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(reconnectedReplica.state, clientStore.loadLatestValid())
                }
            }
        }
    }

    private fun finishEncounter(
        encounter: Int,
        host: HostReplica,
        handler: StormglassGameplayCommandHandler,
        client: LanClientConnection,
        clientReplica: ClientReplica,
    ) {
        var step = 2
        while (host.state.activeCombat != null && step <= 20) {
            val current = host.state.activeCombat!!
            if ("p1" !in current.lockedActions && (current.players["p1"]?.hp ?: 0) > 0) {
                handler.handle(
                    GameplayWireCommand.CombatAction(
                        "hunt-lan-e$encounter-r$step-p1",
                        "p1",
                        CombatActionType.SETUP.name,
                    ),
                    50_000L + encounter * 1_000L + step * 2L,
                )
            }
            val afterP1 = host.state.activeCombat ?: break
            if ("p2" !in afterP1.lockedActions && (afterP1.players["p2"]?.hp ?: 0) > 0) {
                client.sendGameplay(
                    GameplayWireCommand.CombatAction(
                        "hunt-lan-e$encounter-r$step-p2",
                        "p2",
                        CombatActionType.FINISHER.name,
                    )
                )
            }
            step++
        }

        assertEquals(null, host.state.activeCombat)
        assertEquals(null, host.state.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG])
        assertEquals(host.state, clientReplica.state)
        assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))
    }

    private fun assertEncounterOutcome(
        world: WorldState,
        hunt: QuestDefinition,
        sibling: QuestDefinition,
        expectedProgress: Int,
        expectedStatus: QuestStatus,
    ) {
        val progress = world.questBoard.active.getValue(hunt.questId)
        assertEquals(expectedProgress, progress.progress)
        assertEquals(expectedStatus, progress.status)
        assertEquals(0, world.questBoard.active.getValue(sibling.questId).progress)
    }

    private fun assertNoEarlyReward(
        world: WorldState,
        berriesBefore: Long,
        bountiesBefore: Map<String, Long>,
        p1InventoryBefore: grandlineduo.game.InventoryState,
        p2InventoryBefore: grandlineduo.game.InventoryState,
        flagsBefore: Map<String, String>,
    ) {
        assertEquals(berriesBefore, world.partyBerries)
        assertEquals(bountiesBefore, world.players.mapValues { it.value.bounty })
        assertEquals(p1InventoryBefore, InventoryEngine.read(world, "p1"))
        assertEquals(p2InventoryBefore, InventoryEngine.read(world, "p2"))
        assertEquals(flagsBefore, world.worldFlags)
    }

    private fun huntWorld(id: String, hunt: QuestDefinition, sibling: QuestDefinition): WorldState {
        val p1Profile = profile("Kairo")
        val p2Profile = profile("Namiya")
        val base = WorldState(
            campaignId = id,
            islandId = hunt.islandId,
            partyBerries = 2_500,
            players = mapOf(
                "p1" to PlayerState(
                    "p1", p1Profile.name, 90, 100, 40_000_000L,
                    p1Profile.maxEnergy - 2, p1Profile.maxEnergy, p1Profile,
                ),
                "p2" to PlayerState(
                    "p2", p2Profile.name, 88, 100, 35_000_000L,
                    p2Profile.maxEnergy - 3, p2Profile.maxEnergy, p2Profile,
                ),
            ),
            questBoard = QuestBoardState(
                active = mapOf(
                    hunt.questId to QuestProgress(hunt, QuestStatus.ACTIVE, 0, "p2"),
                    sibling.questId to QuestProgress(sibling, QuestStatus.ACTIVE, 0, "p1"),
                ),
            ),
            worldFlags = mapOf(
                "campaign.mode" to "HOST_COOP",
                "campaign.chapter" to "0",
                "HAS_LOG_POSE" to "1",
            ),
        )
        var world = StormglassPersistenceAdapter.encode(base, ScenarioState(stage = ScenarioStage.COMPLETE), null)
        listOf("p1", "p2").forEach { playerId ->
            world = InventoryEngine.grant(world, playerId, "iron_sabre", 1)
            world = InventoryEngine.equip(world, playerId, "iron_sabre")
            world = InventoryEngine.grant(world, playerId, "marine_vest", 1)
            world = InventoryEngine.equip(world, playerId, "marine_vest")
        }
        return world
    }

    private fun profile(name: String) =
        (CharacterCreation.create(CharacterCreationTest.validDraft().copy(name = name)) as CharacterCreationResult.Success).profile

    private fun huntQuest(id: String, reward: QuestReward) = QuestDefinition(
        questId = id,
        islandId = "ironwake-atoll",
        title = "Caçada aos saqueadores do cais",
        type = QuestType.HUNT,
        rarity = QuestRarity.COMMON,
        issuerFaction = "CIVILIANS",
        targetId = "dock-raiders",
        requiredAmount = 3,
        reward = reward,
    )
}
