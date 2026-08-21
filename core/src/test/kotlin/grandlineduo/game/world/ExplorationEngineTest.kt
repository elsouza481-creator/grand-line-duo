package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationEngineTest {
    fun register() {
        test("island exploration map is deterministic and keeps a walkable spawn") {
            val a = ExplorationEngine.mapFor("explore-seed", "stormglass-cay")
            val b = ExplorationEngine.mapFor("explore-seed", "stormglass-cay")
            val other = ExplorationEngine.mapFor("explore-seed", "emberwake")

            assertEquals(a, b)
            assertNotEquals(a.tiles, other.tiles)
            assertEquals(24, a.width)
            assertEquals(18, a.height)
            assertTrue(a.isWalkable(a.spawn))
        }

        test("hub map exposes physical dock market training ship and crew interaction points") {
            val map = ExplorationEngine.mapFor("explore-points", "gearfall")
            val types = map.interactions.values.toSet()

            assertTrue(ExplorationInteraction.DOCK in types)
            assertTrue(ExplorationInteraction.MARKET in types)
            assertTrue(ExplorationInteraction.TRAINING in types)
            assertTrue(ExplorationInteraction.SHIP in types)
            assertTrue(ExplorationInteraction.CREW in types)
        }

        test("player begins at map spawn and moves one cardinal tile authoritatively") {
            val world = world("explore-move")
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val start = ExplorationEngine.position(world, "p1")
            assertEquals(map.spawn, start)

            val moved = ExplorationEngine.move(world, "p1", ExplorationDirection.EAST)
            assertEquals(GridPosition(start.x + 1, start.y), ExplorationEngine.position(moved, "p1"))
        }

        test("exploration rejects diagonal client movement and cannot cross blocked terrain") {
            val world = world("explore-collision")
            var rejected = false
            try {
                ExplorationEngine.moveBy(world, "p1", 1, 1)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)

            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val edge = map.tiles.keys.first { position ->
                map.isWalkable(position) && ExplorationDirection.entries.any { direction ->
                    !map.isWalkable(position + direction)
                }
            }
            val direction = ExplorationDirection.entries.first { !map.isWalkable(edge + it) }
            val positioned = ExplorationEngine.place(world, "p1", edge)
            val blocked = ExplorationEngine.move(positioned, "p1", direction)
            assertEquals(edge, ExplorationEngine.position(blocked, "p1"))
        }

        test("interaction is resolved from the player's current physical tile") {
            val world = world("explore-interact")
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val marketTile = map.interactions.entries.first { it.value == ExplorationInteraction.MARKET }.key
            val positioned = ExplorationEngine.place(world, "p1", marketTile)

            assertEquals(ExplorationInteraction.MARKET, ExplorationEngine.interactionAt(positioned, "p1"))
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
