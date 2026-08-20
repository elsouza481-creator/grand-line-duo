package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.game.InventoryEngine
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationLootCommandIntegrationTest {
    fun register() {
        test("authoritative loot command ignores client amount and retry cannot duplicate reward") {
            var initial = world("loot-command")
            val pickup = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).pickups.values.single()
            initial = ExplorationEngine.place(initial, "p1", pickup.position)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 91)
            val command = GameplayWireCommand.WorldAction("loot-once", "p1", "LOOT_COLLECT", pickup.id, 999)

            handler.handle(command, 1)
            handler.handle(command, 2)

            assertTrue(ExplorationLootEngine.isCollected(host.state, pickup.id))
            assertEquals(initial.partyBerries + pickup.berries, host.state.partyBerries)
            assertEquals(pickup.amount, InventoryEngine.read(host.state, "p1").items[pickup.itemId])
        }

        test("P2 collects shared map loot over real TCP and host converges") {
            var initial = world("loot-lan")
            val pickup = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).pickups.values.single()
            initial = ExplorationEngine.place(initial, "p2", pickup.position)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 92)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    client.sendGameplay(GameplayWireCommand.WorldAction("p2-loot", "p2", "LOOT_COLLECT", pickup.id, 500))

                    assertEquals(host.state, clientReplica.state)
                    assertTrue(ExplorationLootEngine.isCollected(host.state, pickup.id))
                    assertEquals(pickup.amount, InventoryEngine.read(host.state, "p2").items[pickup.itemId])
                    assertEquals(null, InventoryEngine.read(host.state, "p1").items[pickup.itemId])
                }
            }
        }
    }

    private fun world(campaignId: String) = WorldState(
        campaignId = campaignId,
        islandId = "stormglass-cay",
        partyBerries = 500,
        players = mapOf(
            "p1" to PlayerState("p1", "A", 30, 30, 0),
            "p2" to PlayerState("p2", "B", 30, 30, 0),
        ),
    )
}
