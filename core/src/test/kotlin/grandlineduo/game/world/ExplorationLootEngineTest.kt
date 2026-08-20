package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationLootEngineTest {
    fun register() {
        test("each island exposes a deterministic walkable physical loot cache") {
            val a = ExplorationEngine.mapFor("loot-seed", "stormglass-cay")
            val b = ExplorationEngine.mapFor("loot-seed", "stormglass-cay")
            assertEquals(a.pickups, b.pickups)
            val pickup = a.pickups.values.single()
            assertTrue(a.isWalkable(pickup.position))
            assertTrue(pickup.position != a.spawn)
            assertTrue(pickup.position !in a.interactions)
            assertTrue(pickup.position !in a.npcs)
            assertTrue(pickup.position !in a.questObjectives)
        }

        test("loot cache requires its physical tile and can be collected only once for the party") {
            var world = world("loot-physical")
            val pickup = ExplorationEngine.mapFor(world.campaignId, world.islandId).pickups.values.single()

            var rejected = false
            try {
                ExplorationLootEngine.collect(world, "p1", pickup.id)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected, "Loot must require standing on its physical tile")

            world = ExplorationEngine.place(world, "p1", pickup.position)
            val berriesBefore = world.partyBerries
            world = ExplorationLootEngine.collect(world, "p1", pickup.id)
            assertTrue(ExplorationLootEngine.isCollected(world, pickup.id))
            assertEquals(berriesBefore + pickup.berries, world.partyBerries)
            assertEquals(pickup.amount, InventoryEngine.read(world, "p1").items[pickup.itemId])

            world = ExplorationEngine.place(world, "p2", pickup.position)
            rejected = false
            try {
                ExplorationLootEngine.collect(world, "p2", pickup.id)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected, "A shared world cache must not duplicate loot for P2")
            assertEquals(null, InventoryEngine.read(world, "p2").items[pickup.itemId])
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
