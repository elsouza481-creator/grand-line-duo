package grandlineduo.game.world

import grandlineduo.core.model.WorldState
import java.util.Random
import kotlin.math.abs

enum class ExplorationTile(val walkable: Boolean) {
    WATER(false),
    GRASS(true),
    SAND(true),
    STONE(true),
    ROAD(true),
    BUILDING(false),
}

enum class ExplorationInteraction {
    DOCK,
    MARKET,
    TRAINING,
    SHIP,
    CREW,
}

enum class ExplorationDirection(val dx: Int, val dy: Int) {
    NORTH(0, -1),
    SOUTH(0, 1),
    WEST(-1, 0),
    EAST(1, 0),
}

data class GridPosition(val x: Int, val y: Int) {
    operator fun plus(direction: ExplorationDirection): GridPosition =
        GridPosition(x + direction.dx, y + direction.dy)
}

data class ExplorationMap(
    val width: Int,
    val height: Int,
    val tiles: Map<GridPosition, ExplorationTile>,
    val spawn: GridPosition,
    val interactions: Map<GridPosition, ExplorationInteraction>,
) {
    fun tileAt(position: GridPosition): ExplorationTile = tiles[position] ?: ExplorationTile.WATER
    fun isWalkable(position: GridPosition): Boolean = tileAt(position).walkable
}

/**
 * Lightweight deterministic tile exploration used by the Android top-down hub.
 * Position is stored in authoritative world flags so LAN peers, save files and reconnects converge.
 */
object ExplorationEngine {
    private const val WIDTH = 24
    private const val HEIGHT = 18
    private val SPAWN = GridPosition(WIDTH / 2, HEIGHT / 2)

    fun mapFor(campaignId: String, islandId: String): ExplorationMap {
        require(campaignId.isNotBlank()) { "Campaign id is required" }
        require(islandId.isNotBlank()) { "Island id is required" }
        val random = Random(seed(campaignId, islandId))
        val tiles = linkedMapOf<GridPosition, ExplorationTile>()

        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val position = GridPosition(x, y)
                val border = x == 0 || y == 0 || x == WIDTH - 1 || y == HEIGHT - 1
                tiles[position] = if (border) {
                    ExplorationTile.WATER
                } else {
                    when (random.nextInt(10)) {
                        0 -> ExplorationTile.SAND
                        1 -> ExplorationTile.STONE
                        2 -> ExplorationTile.BUILDING
                        else -> ExplorationTile.GRASS
                    }
                }
            }
        }

        // Guaranteed connected town cross and central plaza.
        for (x in 1 until WIDTH - 1) tiles[GridPosition(x, SPAWN.y)] = ExplorationTile.ROAD
        for (y in 1 until HEIGHT - 1) tiles[GridPosition(SPAWN.x, y)] = ExplorationTile.ROAD
        for (y in SPAWN.y - 2..SPAWN.y + 2) {
            for (x in SPAWN.x - 3..SPAWN.x + 3) tiles[GridPosition(x, y)] = ExplorationTile.ROAD
        }

        val interactions = linkedMapOf(
            GridPosition(SPAWN.x, HEIGHT - 2) to ExplorationInteraction.DOCK,
            GridPosition(SPAWN.x - 2, SPAWN.y - 1) to ExplorationInteraction.MARKET,
            GridPosition(SPAWN.x + 2, SPAWN.y - 1) to ExplorationInteraction.TRAINING,
            GridPosition(SPAWN.x - 1, SPAWN.y + 1) to ExplorationInteraction.SHIP,
            GridPosition(SPAWN.x + 1, SPAWN.y + 1) to ExplorationInteraction.CREW,
        )
        interactions.keys.forEach { tiles[it] = ExplorationTile.ROAD }
        tiles[SPAWN] = ExplorationTile.ROAD
        tiles[SPAWN + ExplorationDirection.EAST] = ExplorationTile.ROAD

        return ExplorationMap(WIDTH, HEIGHT, tiles.toMap(), SPAWN, interactions.toMap())
    }

    fun position(world: WorldState, playerId: String): GridPosition {
        require(playerId in world.players) { "Unknown player $playerId" }
        val map = mapFor(world.campaignId, world.islandId)
        val x = world.worldFlags[positionKey(world.islandId, playerId, "x")]?.toIntOrNull()
        val y = world.worldFlags[positionKey(world.islandId, playerId, "y")]?.toIntOrNull()
        val stored = if (x != null && y != null) GridPosition(x, y) else null
        return stored?.takeIf(map::isWalkable) ?: map.spawn
    }

    fun place(world: WorldState, playerId: String, position: GridPosition): WorldState {
        require(playerId in world.players) { "Unknown player $playerId" }
        val map = mapFor(world.campaignId, world.islandId)
        require(map.isWalkable(position)) { "Exploration position is blocked" }
        return world.copy(
            worldFlags = world.worldFlags + mapOf(
                positionKey(world.islandId, playerId, "x") to position.x.toString(),
                positionKey(world.islandId, playerId, "y") to position.y.toString(),
            )
        )
    }

    fun move(world: WorldState, playerId: String, direction: ExplorationDirection): WorldState {
        val current = position(world, playerId)
        val target = current + direction
        val map = mapFor(world.campaignId, world.islandId)
        return if (map.isWalkable(target)) place(world, playerId, target) else world
    }

    fun moveBy(world: WorldState, playerId: String, dx: Int, dy: Int): WorldState {
        require(abs(dx) + abs(dy) == 1) { "Exploration movement must be one cardinal tile" }
        val direction = ExplorationDirection.entries.first { it.dx == dx && it.dy == dy }
        return move(world, playerId, direction)
    }

    fun interactionAt(world: WorldState, playerId: String): ExplorationInteraction? {
        val map = mapFor(world.campaignId, world.islandId)
        return map.interactions[position(world, playerId)]
    }

    private fun positionKey(islandId: String, playerId: String, axis: String) =
        "explore.$islandId.$playerId.$axis"

    private fun seed(campaignId: String, islandId: String): Long {
        var hash = 0xCBF29CE484222325UL.toLong()
        "$campaignId|$islandId|exploration-v1".forEach { ch ->
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001B3L
        }
        return hash
    }
}
