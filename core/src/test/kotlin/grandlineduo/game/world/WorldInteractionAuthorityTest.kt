package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.InventoryEngine
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.ship.ShipEngine
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object WorldInteractionAuthorityTest {
    fun register() {
        test("market ship and crew actions are rejected away from their physical world tiles") {
            val cases = listOf(
                GameplayWireCommand.WorldAction("blocked-market", "p1", "SHOP_BUY", "bandage", 1),
                GameplayWireCommand.WorldAction("blocked-ship", "p1", "SHIP_REPAIR", "", 2),
                GameplayWireCommand.WorldAction("blocked-crew", "p1", "CREW_RECRUIT", "mara-tide", 1),
            )

            cases.forEachIndexed { index, command ->
                val initial = baseWorld("blocked-$index")
                val host = HostReplica(initial)
                val handler = StormglassGameplayCommandHandler(host, seed = 80L + index)
                var rejected = false
                try {
                    handler.handle(command, index.toLong() + 1)
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                assertTrue(rejected, "${command.actionType} must require its physical tile")
                assertEquals(initial, host.state, "rejected physical action must not mutate world")
            }
        }

        test("market service works after the player physically reaches the market") {
            val initial = atInteraction(baseWorld("market-ok"), "p1", ExplorationInteraction.MARKET)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 91)

            handler.handle(GameplayWireCommand.WorldAction("market-ok", "p1", "SHOP_BUY", "bandage", 1), 1)

            assertEquals(1, InventoryEngine.read(host.state, "p1").items["bandage"])
            assertEquals(initial.partyBerries - 250L, host.state.partyBerries)
        }

        test("ship service works after the player physically reaches the moored ship") {
            val initial = atInteraction(baseWorld("ship-ok"), "p1", ExplorationInteraction.SHIP)
            val beforeHull = initial.shipState!!.hull
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 92)

            handler.handle(GameplayWireCommand.WorldAction("ship-ok", "p1", "SHIP_REPAIR", "", 3), 1)

            assertEquals(beforeHull + 3, host.state.shipState!!.hull)
        }

        test("crew recruitment works after the player physically reaches the crew point") {
            val initial = atInteraction(baseWorld("crew-ok"), "p1", ExplorationInteraction.CREW)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 93)

            handler.handle(GameplayWireCommand.WorldAction("crew-ok", "p1", "CREW_RECRUIT", "mara-tide", 1), 1)

            assertTrue("mara-tide" in host.state.crewState.members)
        }
    }

    private fun baseWorld(id: String): WorldState {
        val ship = ShipEngine.damage(ShipEngine.starterShip("ship-$id", "Vento Livre"), 10)
        return WorldState(
            campaignId = id,
            islandId = "stormglass-cay",
            partyBerries = 20_000,
            players = mapOf(
                "p1" to PlayerState("p1", "A", 30, 30, 0),
                "p2" to PlayerState("p2", "B", 30, 30, 0),
            ),
            shipState = ship,
        )
    }

    private fun atInteraction(world: WorldState, playerId: String, interaction: ExplorationInteraction): WorldState {
        val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
        val tile = map.interactions.entries.first { it.value == interaction }.key
        return ExplorationEngine.place(world, playerId, tile)
    }
}
