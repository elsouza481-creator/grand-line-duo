package grandlineduo.game.world

import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.EnemyAttackType
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

data class ExplorationNpc(
    val id: String,
    val name: String,
    val title: String,
    val position: GridPosition,
    val questId: String? = null,
    val dialogue: String,
)

data class ExplorationQuestObjective(
    val questId: String,
    val position: GridPosition,
    val label: String,
)

data class ExplorationPickup(
    val id: String,
    val position: GridPosition,
    val label: String,
    val itemId: String,
    val amount: Int,
    val berries: Long,
)

data class ExplorationEnemy(
    val id: String,
    val name: String,
    val archetype: ExplorationEnemyArchetype,
    val position: GridPosition,
    val maxHp: Int,
    val attackPower: Int,
    val rewardBerries: Long,
    val rewardItemId: String,
    val rewardItemAmount: Int,
    val initialAttackType: EnemyAttackType,
)

data class ExplorationMap(
    val width: Int,
    val height: Int,
    val tiles: Map<GridPosition, ExplorationTile>,
    val spawn: GridPosition,
    val interactions: Map<GridPosition, ExplorationInteraction>,
    val npcs: Map<GridPosition, ExplorationNpc> = emptyMap(),
    val questObjectives: Map<GridPosition, ExplorationQuestObjective> = emptyMap(),
    val pickups: Map<GridPosition, ExplorationPickup> = emptyMap(),
    val enemies: Map<GridPosition, ExplorationEnemy> = emptyMap(),
) {
    fun tileAt(position: GridPosition): ExplorationTile = tiles[position] ?: ExplorationTile.WATER
    fun isWalkable(position: GridPosition): Boolean = tileAt(position).walkable
}

object ExplorationEngine {
    private const val WIDTH = 24
    private const val HEIGHT = 18
    private val SPAWN = GridPosition(WIDTH / 2, HEIGHT / 2)
    private val QUEST_GIVER_NAMES = listOf("Iria", "Bram", "Noa", "Tess", "Kellan", "Suri")

    fun mapFor(campaignId: String, islandId: String): ExplorationMap {
        require(campaignId.isNotBlank()) { "Campaign id is required" }
        require(islandId.isNotBlank()) { "Island id is required" }
        val mapSeed = seed(campaignId, islandId)
        val random = Random(mapSeed)
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

        val questGiverPosition = GridPosition(SPAWN.x - 4, SPAWN.y)
        val questObjectivePosition = GridPosition(SPAWN.x + 5, SPAWN.y)
        tiles[questGiverPosition] = ExplorationTile.ROAD
        tiles[questObjectivePosition] = ExplorationTile.ROAD
        val questId = "local-cache-$islandId"
        val npcName = QUEST_GIVER_NAMES[((mapSeed xor (mapSeed ushr 32)).toInt() and Int.MAX_VALUE) % QUEST_GIVER_NAMES.size]
        val questGiver = ExplorationNpc(
            id = "wayfinder-$islandId",
            name = npcName,
            title = "Batedor local",
            position = questGiverPosition,
            questId = questId,
            dialogue = "Perdi uma caixa marcada na estrada leste. Encontre-a e volte aqui; pago pela recuperação.",
        )
        val objective = ExplorationQuestObjective(
            questId = questId,
            position = questObjectivePosition,
            label = "Caixa perdida de $npcName",
        )

        val pickupPosition = GridPosition(SPAWN.x, SPAWN.y - 4)
        tiles[pickupPosition] = ExplorationTile.ROAD
        val pickup = ExplorationPickup(
            id = "field-cache-$islandId",
            position = pickupPosition,
            label = "Baú de suprimentos esquecido",
            itemId = "bandage",
            amount = 1,
            berries = 350L,
        )

        val danger = GrandLineWorldAtlas.describe(campaignId, islandId).danger.coerceIn(1, 10)
        val enemyPosition = GridPosition(SPAWN.x + 7, SPAWN.y)
        tiles[enemyPosition] = ExplorationTile.ROAD
        val archetype = ExplorationEnemyCatalog.select(campaignId, islandId)
        val profile = ExplorationEnemyCatalog.profile(archetype, danger)
        val enemy = ExplorationEnemy(
            id = "road-hostile-$islandId",
            name = profile.name,
            archetype = archetype,
            position = enemyPosition,
            maxHp = profile.maxHp,
            attackPower = profile.attackPower,
            rewardBerries = profile.rewardBerries,
            rewardItemId = profile.rewardItemId,
            rewardItemAmount = profile.rewardItemAmount,
            initialAttackType = profile.initialAttackType,
        )

        return ExplorationMap(
            width = WIDTH,
            height = HEIGHT,
            tiles = tiles.toMap(),
            spawn = SPAWN,
            interactions = interactions.toMap(),
            npcs = mapOf(questGiver.position to questGiver),
            questObjectives = mapOf(objective.position to objective),
            pickups = mapOf(pickup.position to pickup),
            enemies = mapOf(enemy.position to enemy),
        )
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

    fun npcAt(world: WorldState, playerId: String): ExplorationNpc? {
        val map = mapFor(world.campaignId, world.islandId)
        return map.npcs[position(world, playerId)]
    }

    fun questObjectiveAt(world: WorldState, playerId: String): ExplorationQuestObjective? {
        val map = mapFor(world.campaignId, world.islandId)
        return map.questObjectives[position(world, playerId)]
    }

    fun pickupAt(world: WorldState, playerId: String): ExplorationPickup? {
        val map = mapFor(world.campaignId, world.islandId)
        return map.pickups[position(world, playerId)]
    }

    fun enemyAt(world: WorldState, playerId: String): ExplorationEnemy? {
        val map = mapFor(world.campaignId, world.islandId)
        return map.enemies[position(world, playerId)]
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
