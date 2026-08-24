package grandlineduo.game.quest

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.core.network.WireCodec
import grandlineduo.core.network.WireMessage
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object QuestLanIntegrationTest {
    fun register() {
        test("quest action wire command round trips through LAN codec") {
            val command = GameplayWireCommand.QuestAction(
                commandId = "quest-p2-1",
                actorId = "p2",
                actionType = "PROGRESS",
                questId = "ironwake-quest-1",
                amount = 3,
            )
            val message = WireMessage.GameplayCommand(command)

            assertEquals(message, WireCodec.decodeFrame(WireCodec.encodeFrame(message)))
        }

        test("P2 refreshes accepts progresses and turns in shared quest over real TCP") {
            val initial = world("quest-lan")
            val hostDir = Files.createTempDirectory("gld-quest-lan-host")
            val clientDir = Files.createTempDirectory("gld-quest-lan-client")
            val hostStore = SnapshotStore(hostDir)
            val clientStore = SnapshotStore(clientDir)
            val host = HostReplica(initial)
            val clientReplica = ClientReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 412L, snapshotStore = hostStore)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()

                    client.sendGameplay(
                        GameplayWireCommand.QuestAction("quest-refresh", "p2", "REFRESH")
                    )
                    assertEquals(3, host.state.questBoard.offers.size)
                    assertEquals(host.state, clientReplica.state)

                    val quest = host.state.questBoard.offers.values.sortedBy { it.questId }.first()
                    val berriesBefore = host.state.partyBerries
                    client.sendGameplay(
                        GameplayWireCommand.QuestAction("quest-accept", "p2", "ACCEPT", quest.questId)
                    )
                    assertEquals("p2", host.state.questBoard.active.getValue(quest.questId).acceptedBy)

                    client.sendGameplay(
                        GameplayWireCommand.QuestAction(
                            "quest-progress",
                            "p2",
                            "PROGRESS",
                            quest.questId,
                            quest.requiredAmount,
                        )
                    )
                    assertEquals(
                        QuestStatus.READY_TO_TURN_IN,
                        host.state.questBoard.active.getValue(quest.questId).status,
                    )

                    client.sendGameplay(
                        GameplayWireCommand.QuestAction("quest-turn-in", "p2", "TURN_IN", quest.questId)
                    )
                    assertTrue(quest.questId in host.state.questBoard.completedQuestIds)
                    assertTrue(host.state.partyBerries > berriesBefore)
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                }
            }
        }

        test("P2 quest boss survives reconnect converges and rewards exactly once over real TCP") {
            val quest = bossQuest()
            val initial = bossWorld("quest-boss-lan", quest)
            val hostDir = Files.createTempDirectory("gld-quest-boss-lan-host")
            val clientDir = Files.createTempDirectory("gld-quest-boss-lan-client")
            val hostStore = SnapshotStore(hostDir)
            val clientStore = SnapshotStore(clientDir)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 7331L, snapshotStore = hostStore)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                val firstReplica = ClientReplica(initial)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", firstReplica, clientStore).use { firstClient ->
                    firstClient.connect()
                    firstClient.sendGameplay(
                        GameplayWireCommand.QuestAction("boss-accept", "p2", "ACCEPT", quest.questId)
                    )
                    firstClient.sendGameplay(
                        GameplayWireCommand.QuestAction("boss-start", "p2", "START_BOSS", quest.questId)
                    )

                    assertEquals(quest.questId, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
                    assertEquals(host.state, firstReplica.state)
                    assertEquals(host.state, clientStore.loadLatestValid())
                    firstClient.disconnect()
                }

                handler.handle(
                    GameplayWireCommand.CombatAction("boss-round1-p1", "p1", CombatActionType.SETUP.name),
                    30_001L,
                )
                assertEquals(
                    CombatActionType.SETUP,
                    host.state.activeCombat!!.lockedActions.getValue("p1").type,
                )

                val persistedClientState = clientStore.loadLatestValid()!!
                val reconnectedReplica = ClientReplica(persistedClientState)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", reconnectedReplica, clientStore).use { client ->
                    client.connect()
                    assertEquals(host.state, reconnectedReplica.state)
                    assertEquals(quest.questId, reconnectedReplica.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
                    assertEquals(
                        CombatActionType.SETUP,
                        reconnectedReplica.state.activeCombat!!.lockedActions.getValue("p1").type,
                    )

                    client.sendGameplay(
                        GameplayWireCommand.CombatAction("boss-round1-p2", "p2", CombatActionType.FINISHER.name)
                    )

                    var round = 2
                    while (host.state.activeCombat != null && round <= 20) {
                        handler.handle(
                            GameplayWireCommand.CombatAction("boss-round${round}-p1", "p1", CombatActionType.SETUP.name),
                            30_000L + round * 2,
                        )
                        client.sendGameplay(
                            GameplayWireCommand.CombatAction(
                                "boss-round${round}-p2",
                                "p2",
                                CombatActionType.FINISHER.name,
                            )
                        )
                        round++
                    }

                    assertEquals(null, host.state.activeCombat)
                    assertEquals(
                        QuestStatus.READY_TO_TURN_IN,
                        host.state.questBoard.active.getValue(quest.questId).status,
                    )
                    assertEquals(null, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
                    assertEquals(host.state, reconnectedReplica.state)
                    assertEquals(
                        CanonicalStateHasher.hash(host.state),
                        CanonicalStateHasher.hash(reconnectedReplica.state),
                    )

                    val berriesBeforeTurnIn = host.state.partyBerries
                    val turnIn = GameplayWireCommand.QuestAction(
                        "boss-turn-in",
                        "p2",
                        "TURN_IN",
                        quest.questId,
                    )
                    client.sendGameplay(turnIn)
                    val berriesAfterTurnIn = host.state.partyBerries
                    assertEquals(berriesBeforeTurnIn + quest.reward.berries, berriesAfterTurnIn)
                    assertTrue(quest.questId in host.state.questBoard.completedQuestIds)

                    client.sendGameplay(turnIn)
                    assertEquals(berriesAfterTurnIn, host.state.partyBerries)
                    assertEquals(host.state, reconnectedReplica.state)
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(reconnectedReplica.state, clientStore.loadLatestValid())
                }
            }
        }
    }

    private fun bossQuest() = QuestDefinition(
        questId = "ironwake-boss-contract",
        islandId = "ironwake-atoll",
        title = "Derrubar o executor do porto",
        type = QuestType.BOSS,
        rarity = QuestRarity.COMMON,
        issuerFaction = "CIVILIANS",
        targetId = "port-enforcer",
        requiredAmount = 1,
        reward = QuestReward(berries = 4_000),
    )

    private fun bossWorld(id: String, quest: QuestDefinition) = WorldState(
        campaignId = id,
        islandId = "ironwake-atoll",
        partyBerries = 1_500,
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 180, 180, 40_000_000L),
            "p2" to PlayerState("p2", "Namiya", 180, 180, 35_000_000L),
        ),
        questBoard = QuestBoardState(offers = mapOf(quest.questId to quest)),
        worldFlags = mapOf("HAS_LOG_POSE" to "1"),
    )

    private fun world(id: String) = WorldState(
        campaignId = id,
        islandId = "ironwake-atoll",
        partyBerries = 1_500,
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 32, 32, 40_000_000L),
            "p2" to PlayerState("p2", "Namiya", 28, 28, 35_000_000L),
        ),
        worldFlags = mapOf("HAS_LOG_POSE" to "1"),
    )
}