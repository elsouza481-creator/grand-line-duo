package grandlineduo.game

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.ship.ShipEngine
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object WorldActionIntegrationTest {
    fun register() {
        test("authoritative world actions buy items repair ship and recruit crew") {
            val initialShip = ShipEngine.damage(ShipEngine.starterShip("ship", "Vento Livre"), 10)
            var initial = WorldState(
                campaignId = "world-actions",
                islandId = "stormglass-cay",
                partyBerries = 20_000,
                players = mapOf(
                    "p1" to PlayerState("p1", "A", 30, 30, 0),
                    "p2" to PlayerState("p2", "B", 30, 30, 0),
                ),
                shipState = initialShip,
            )
            val market = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).interactions.entries.single { it.value == ExplorationInteraction.MARKET }.key
            initial = ExplorationEngine.place(initial, "p2", market)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 9)

            handler.handle(GameplayWireCommand.WorldAction("buy", "p2", "SHOP_BUY", "bandage", 2), 1)
            assertEquals(2, InventoryEngine.read(host.state, "p2").items["bandage"])

            // MARKET -> SHIP through the guaranteed plaza roads.
            handler.handle(GameplayWireCommand.WorldAction("move-ship-1", "p2", "EXPLORE_MOVE", "EAST", 999), 2)
            handler.handle(GameplayWireCommand.WorldAction("move-ship-2", "p2", "EXPLORE_MOVE", "SOUTH", 999), 3)
            handler.handle(GameplayWireCommand.WorldAction("move-ship-3", "p2", "EXPLORE_MOVE", "SOUTH", 999), 4)
            assertEquals(ExplorationInteraction.SHIP, ExplorationEngine.interactionAt(host.state, "p2"))
            handler.handle(GameplayWireCommand.WorldAction("repair", "p2", "SHIP_REPAIR", "", 5), 5)
            assertEquals(initialShip.hull + 5, host.state.shipState!!.hull)

            // SHIP -> CREW on the same harbor row.
            handler.handle(GameplayWireCommand.WorldAction("move-crew-1", "p2", "EXPLORE_MOVE", "EAST", 999), 6)
            handler.handle(GameplayWireCommand.WorldAction("move-crew-2", "p2", "EXPLORE_MOVE", "EAST", 999), 7)
            assertEquals(ExplorationInteraction.CREW, ExplorationEngine.interactionAt(host.state, "p2"))
            handler.handle(GameplayWireCommand.WorldAction("crew", "p2", "CREW_RECRUIT", "mara-tide", 1), 8)
            assertTrue("mara-tide" in host.state.crewState.members)
        }

        test("world action retry is idempotent") {
            var initial = WorldState(
                campaignId = "world-retry", islandId = "stormglass-cay", partyBerries = 1_000,
                players = mapOf("p1" to PlayerState("p1", "A", 30, 30, 0), "p2" to PlayerState("p2", "B", 30, 30, 0)),
            )
            val market = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).interactions.entries.single { it.value == ExplorationInteraction.MARKET }.key
            initial = ExplorationEngine.place(initial, "p1", market)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 3)
            val command = GameplayWireCommand.WorldAction("same", "p1", "SHOP_BUY", "bandage", 1)
            handler.handle(command, 1)
            handler.handle(command, 2)
            assertEquals(750L, host.state.partyBerries)
            assertEquals(1, InventoryEngine.read(host.state, "p1").items["bandage"])
        }
    }
}
