package grandlineduo.game.world

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationViewportTest {
    fun register() {
        test("viewport centers the player while there is map space around them") {
            val map = ExplorationEngine.mapFor("viewport", "stormglass-cay")
            val viewport = ExplorationViewport.build(map, map.spawn, width = 11, height = 9)

            assertEquals(11, viewport.width)
            assertEquals(9, viewport.height)
            assertEquals(map.spawn, viewport.playerPosition)
            assertEquals(GridPosition(map.spawn.x - 5, map.spawn.y - 4), viewport.origin)
            assertTrue(viewport.cells.any { it.position == map.spawn && it.isPlayer })
        }

        test("viewport clamps to map bounds and never invents positions outside the island") {
            val map = ExplorationEngine.mapFor("viewport-edge", "brineveil")
            val edge = GridPosition(1, 1)
            val viewport = ExplorationViewport.build(map, edge, width = 11, height = 9)

            assertEquals(GridPosition(0, 0), viewport.origin)
            assertEquals(11 * 9, viewport.cells.size)
            assertTrue(viewport.cells.all { it.position.x in 0 until map.width && it.position.y in 0 until map.height })
        }

        test("viewport carries terrain interaction and player marker for rendering") {
            val map = ExplorationEngine.mapFor("viewport-marker", "gearfall")
            val market = map.interactions.entries.first { it.value == ExplorationInteraction.MARKET }.key
            val viewport = ExplorationViewport.build(map, market, width = 9, height = 7)
            val playerCell = viewport.cells.single { it.isPlayer }

            assertEquals(market, playerCell.position)
            assertEquals(map.tileAt(market), playerCell.tile)
            assertEquals(ExplorationInteraction.MARKET, playerCell.interaction)
        }

        test("viewport projects all four party members while centering the local player") {
            val map = ExplorationEngine.mapFor("viewport-party", "gearfall")
            val positions = mapOf(
                "p1" to map.spawn,
                "p2" to GridPosition(map.spawn.x - 1, map.spawn.y),
                "p3" to GridPosition(map.spawn.x, map.spawn.y - 1),
                "p4" to GridPosition(map.spawn.x + 1, map.spawn.y),
            )
            val viewport = ExplorationViewport.build(
                map = map,
                playerPosition = positions.getValue("p3"),
                playerPositions = positions,
                width = 11,
                height = 9,
            )

            positions.forEach { (playerId, position) ->
                val cell = viewport.cells.single { it.position == position }
                assertTrue(playerId in cell.playerIds)
            }
            assertTrue(viewport.cells.single { it.position == positions.getValue("p3") }.isPlayer)
            assertEquals(positions, viewport.playerPositions)
        }
    }
}
