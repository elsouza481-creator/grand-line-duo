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
    }
}
