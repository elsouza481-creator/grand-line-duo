package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationCommandIntegrationTest {
    fun register() {
        test("authoritative exploration movement advances exactly one tile and ignores client supplied amount") {
            val initial = world("explore-command")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 41)
            val start = ExplorationEngine.position(initial, "p1")
            val partnerStart = ExplorationEngine.position(initial, "p2")

            val event = handler.handle(
                GameplayWireCommand.WorldAction("move-east", "p1", "EXPLORE_MOVE", "EAST", 999),
                1,
            )

            assertEquals(start + ExplorationDirection.EAST, ExplorationEngine.position(host.state, "p1"))
            assertEquals(partnerStart, ExplorationEngine.position(host.state, "p2"))
            assertEquals("EXPLORE_MOVE", event.payload["meta.worldAction"])
            assertEquals("EAST", event.payload["meta.worldTarget"])
        }

        test("invalid exploration direction is rejected without mutating authoritative state") {
            val initial = world("explore-invalid")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 42)
            var rejected = false

            try {
                handler.handle(
                    GameplayWireCommand.WorldAction("move-bad", "p1", "EXPLORE_MOVE", "NORTHEAST", 1),
                    1,
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }

            assertTrue(rejected)
            assertEquals(initial, host.state)
        }

        test("P2 exploration movement crosses real TCP and converges with host") {
            val initial = world("explore-lan")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 43)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    client.sendGameplay(
                        GameplayWireCommand.WorldAction("p2-move", "p2", "EXPLORE_MOVE", "EAST", 500)
                    )

                    assertEquals(host.state, clientReplica.state)
                    val expected = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).spawn + ExplorationDirection.EAST
                    assertEquals(expected, ExplorationEngine.position(host.state, "p2"))
                }
            }
        }
    }

    private fun world(id: String) = WorldState(
        campaignId = id,
        islandId = "stormglass-cay",
        players = mapOf(
            "p1" to PlayerState("p1", "A", 30, 30, 0),
            "p2" to PlayerState("p2", "B", 30, 30, 0),
        ),
    )
}
