package grandlineduo.game.pvp

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.game.world.GridPosition
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object TrainingDuelLanIntegrationTest {
    fun register() {
        test("P2 accepts and resolves a physical training duel over real TCP") {
            var initial = WorldState(
                campaignId = "duel-lan",
                islandId = "stormglass-cay",
                players = mapOf(
                    "p1" to PlayerState("p1", "Kairo", 30, 30, 0),
                    "p2" to PlayerState("p2", "Namiya", 24, 24, 0),
                ),
            )
            val training = ExplorationEngine.mapFor(initial.campaignId, initial.islandId)
                .interactions.entries.single { it.value == ExplorationInteraction.TRAINING }.key
            initial = ExplorationEngine.place(initial, "p1", training)
            initial = ExplorationEngine.place(initial, "p2", training)

            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 71L)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()

                    handler.handle(
                        GameplayWireCommand.WorldAction("duel-challenge", "p1", "DUEL_CHALLENGE"),
                        1_000,
                    )
                    client.sendGameplay(
                        GameplayWireCommand.WorldAction("duel-accept", "p2", "DUEL_ACCEPT"),
                    )
                    assertEquals(TrainingDuelStatus.ACTIVE, TrainingDuelEngine.state(host.state)?.status)

                    handler.handle(
                        GameplayWireCommand.WorldAction("duel-p1-attack", "p1", "DUEL_ACTION", "ATTACK"),
                        1_001,
                    )
                    client.sendGameplay(
                        GameplayWireCommand.WorldAction("duel-p2-defend", "p2", "DUEL_ACTION", "DEFEND"),
                    )

                    val duel = requireNotNull(TrainingDuelEngine.state(host.state))
                    assertEquals(2, duel.round)
                    assertEquals(21, duel.duelHp.getValue("p2"))
                    assertEquals(30, host.state.players.getValue("p1").hp)
                    assertEquals(24, host.state.players.getValue("p2").hp)
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))

                    val beforeMove = CanonicalStateHasher.hash(host.state)
                    var movementRejected = false
                    try {
                        handler.handle(
                            GameplayWireCommand.WorldAction("duel-move-away", "p1", "EXPLORE_MOVE", "NORTH"),
                            1_002,
                        )
                    } catch (_: IllegalArgumentException) {
                        movementRejected = true
                    }
                    assertTrue(movementRejected)
                    assertEquals(beforeMove, CanonicalStateHasher.hash(host.state))
                }
            }
        }

        test("P3 selects P4 and resolves a private training duel over real TCP") {
            var initial = WorldState(
                campaignId = "duel-lan-four",
                islandId = "stormglass-cay",
                players = mapOf(
                    "p1" to PlayerState("p1", "Kairo", 30, 30, 0),
                    "p2" to PlayerState("p2", "Namiya", 24, 24, 0),
                    "p3" to PlayerState("p3", "Cato", 28, 28, 0),
                    "p4" to PlayerState("p4", "Dara", 22, 22, 0),
                ),
            )
            val training = ExplorationEngine.mapFor(initial.campaignId, initial.islandId)
                .interactions.entries.single { it.value == ExplorationInteraction.TRAINING }.key
            listOf("p1", "p2", "p3", "p4").forEach { playerId ->
                initial = ExplorationEngine.place(initial, playerId, training)
            }

            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 72L)
            val p3Replica = ClientReplica(initial)
            val p4Replica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p3", p3Replica).use { p3 ->
                    LanClientConnection("127.0.0.1", server.boundPort, "p4", p4Replica).use { p4 ->
                        p3.connect()
                        p4.connect()

                        p3.sendGameplay(
                            GameplayWireCommand.WorldAction(
                                "duel-p3-challenge-p4",
                                "p3",
                                "DUEL_CHALLENGE",
                                "p4",
                            ),
                        )
                        p4.refresh()
                        val pending = requireNotNull(TrainingDuelEngine.state(host.state))
                        assertEquals("p3", pending.challengerId)
                        assertEquals("p4", pending.opponentId)

                        p4.sendGameplay(
                            GameplayWireCommand.WorldAction("duel-p4-accept", "p4", "DUEL_ACCEPT"),
                        )
                        p3.refresh()
                        val active = requireNotNull(TrainingDuelEngine.state(host.state))
                        assertEquals(setOf("p3", "p4"), active.duelHp.keys)

                        p3.sendGameplay(
                            GameplayWireCommand.WorldAction("duel-p3-attack", "p3", "DUEL_ACTION", "ATTACK"),
                        )
                        p4.refresh()
                        p4.sendGameplay(
                            GameplayWireCommand.WorldAction("duel-p4-defend", "p4", "DUEL_ACTION", "DEFEND"),
                        )
                        p3.refresh()

                        val resolved = requireNotNull(TrainingDuelEngine.state(host.state))
                        assertEquals(2, resolved.round)
                        assertEquals(setOf("p3", "p4"), resolved.duelHp.keys)
                        assertEquals(28, host.state.players.getValue("p3").hp)
                        assertEquals(22, host.state.players.getValue("p4").hp)
                        assertEquals(30, host.state.players.getValue("p1").hp)
                        assertEquals(24, host.state.players.getValue("p2").hp)

                        p4.refresh()
                        assertEquals(host.state, p3Replica.state)
                        assertEquals(host.state, p4Replica.state)
                        assertEquals(
                            CanonicalStateHasher.hash(host.state),
                            CanonicalStateHasher.hash(p3Replica.state),
                        )
                        assertEquals(
                            CanonicalStateHasher.hash(host.state),
                            CanonicalStateHasher.hash(p4Replica.state),
                        )
                    }
                }
            }
        }

        test("P3 challenges adjacent P4 to field sparring over real TCP and replicas converge") {
            var initial = WorldState(
                campaignId = "duel-lan-field-four",
                islandId = "stormglass-cay",
                players = mapOf(
                    "p1" to PlayerState("p1", "Kairo", 30, 30, 0),
                    "p2" to PlayerState("p2", "Namiya", 24, 24, 0),
                    "p3" to PlayerState("p3", "Cato", 28, 28, 0),
                    "p4" to PlayerState("p4", "Dara", 22, 22, 0),
                ),
            )
            val spawn = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).spawn
            initial = ExplorationEngine.place(initial, "p3", spawn)
            initial = ExplorationEngine.place(initial, "p4", GridPosition(spawn.x + 1, spawn.y))

            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 73L)
            val p3Replica = ClientReplica(initial)
            val p4Replica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p3", p3Replica).use { p3 ->
                    LanClientConnection("127.0.0.1", server.boundPort, "p4", p4Replica).use { p4 ->
                        p3.connect()
                        p4.connect()

                        p3.sendGameplay(
                            GameplayWireCommand.WorldAction(
                                "field-p3-challenge-p4",
                                "p3",
                                "DUEL_FIELD_CHALLENGE",
                                "p4",
                            ),
                        )
                        p4.refresh()
                        val pending = requireNotNull(TrainingDuelEngine.state(host.state))
                        assertEquals(TrainingDuelVenue.FIELD_SPARRING, pending.venue)
                        assertEquals("p3", pending.challengerId)
                        assertEquals("p4", pending.opponentId)

                        p4.sendGameplay(
                            GameplayWireCommand.WorldAction("field-p4-accept", "p4", "DUEL_ACCEPT"),
                        )
                        p3.refresh()
                        assertEquals(TrainingDuelStatus.ACTIVE, TrainingDuelEngine.state(host.state)?.status)

                        p3.sendGameplay(
                            GameplayWireCommand.WorldAction("field-p3-attack", "p3", "DUEL_ACTION", "ATTACK"),
                        )
                        p4.refresh()
                        p4.sendGameplay(
                            GameplayWireCommand.WorldAction("field-p4-defend", "p4", "DUEL_ACTION", "DEFEND"),
                        )
                        p3.refresh()
                        p4.refresh()

                        val resolved = requireNotNull(TrainingDuelEngine.state(host.state))
                        assertEquals(TrainingDuelVenue.FIELD_SPARRING, resolved.venue)
                        assertEquals(2, resolved.round)
                        assertEquals(host.state, p3Replica.state)
                        assertEquals(host.state, p4Replica.state)
                    }
                }
            }
        }
    }
}
