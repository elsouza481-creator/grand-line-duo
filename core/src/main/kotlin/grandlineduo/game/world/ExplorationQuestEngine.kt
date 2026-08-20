package grandlineduo.game.world

import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine

enum class ExplorationQuestStatus {
    AVAILABLE,
    ACTIVE,
    OBJECTIVE_COMPLETE,
    TURNED_IN,
}

/**
 * Per-player lightweight free-roam quest progression. State lives in authoritative world flags,
 * so it is automatically covered by snapshots, event hashes, LAN sync and crash recovery.
 */
object ExplorationQuestEngine {
    const val REWARD_BERRIES: Long = 750L
    const val REWARD_ITEM_ID: String = "ration"
    const val BOSS_REWARD_BERRIES: Long = 2_500L
    const val BOSS_REWARD_ITEM_ID: String = "kairouseki_shard"

    fun bossHuntQuestId(islandId: String): String = "boss-hunt-$islandId"

    fun isBossHuntQuest(questId: String): Boolean = questId.startsWith("boss-hunt-")

    fun status(world: WorldState, playerId: String, questId: String): ExplorationQuestStatus {
        require(playerId in world.players) { "Unknown player $playerId" }
        requireQuest(world, questId)
        val stored = world.worldFlags[statusKey(world, playerId, questId)]
            ?: return ExplorationQuestStatus.AVAILABLE
        return try {
            ExplorationQuestStatus.valueOf(stored)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid quest state for $questId")
        }
    }

    fun accept(world: WorldState, playerId: String, questId: String): WorldState {
        require(status(world, playerId, questId) == ExplorationQuestStatus.AVAILABLE) { "Quest is not available" }
        val npc = ExplorationEngine.npcAt(world, playerId)
            ?: throw IllegalArgumentException("Player must stand on the quest giver tile")
        require(npc.questId == questId) { "This NPC does not offer $questId" }
        return setStatus(world, playerId, questId, ExplorationQuestStatus.ACTIVE)
    }

    fun progress(world: WorldState, playerId: String, questId: String): WorldState {
        require(status(world, playerId, questId) == ExplorationQuestStatus.ACTIVE) { "Quest objective is not active" }
        require(!isBossHuntQuest(questId)) { "Boss hunts advance only after defeating the target" }
        val objective = ExplorationEngine.questObjectiveAt(world, playerId)
            ?: throw IllegalArgumentException("Player must stand on the quest objective tile")
        require(objective.questId == questId) { "This objective does not belong to $questId" }
        return setStatus(world, playerId, questId, ExplorationQuestStatus.OBJECTIVE_COMPLETE)
    }

    fun completeBossHunt(
        world: WorldState,
        survivingPlayerIds: Collection<String>,
        enemyId: String,
    ): WorldState {
        val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
        val boss = map.enemies.values.firstOrNull {
            it.id == enemyId && it.rank == ExplorationEnemyRank.FIELD_BOSS
        } ?: throw IllegalArgumentException("$enemyId is not the field boss on ${world.islandId}")
        val questId = bossHuntQuestId(world.islandId)
        require(map.npcs.values.any { it.questId == questId }) { "Field boss has no hunt quest" }

        var next = world
        survivingPlayerIds.distinct().sorted().forEach { playerId ->
            if (playerId in next.players && status(next, playerId, questId) == ExplorationQuestStatus.ACTIVE) {
                next = setStatus(next, playerId, questId, ExplorationQuestStatus.OBJECTIVE_COMPLETE)
            }
        }
        return next
    }

    fun turnIn(world: WorldState, playerId: String, questId: String): WorldState {
        require(status(world, playerId, questId) == ExplorationQuestStatus.OBJECTIVE_COMPLETE) { "Quest objective is not complete" }
        val npc = ExplorationEngine.npcAt(world, playerId)
            ?: throw IllegalArgumentException("Player must return to the quest giver")
        require(npc.questId == questId) { "This NPC cannot complete $questId" }

        val bossHunt = isBossHuntQuest(questId)
        val berries = if (bossHunt) BOSS_REWARD_BERRIES else REWARD_BERRIES
        val itemId = if (bossHunt) BOSS_REWARD_ITEM_ID else REWARD_ITEM_ID
        var next = setStatus(world, playerId, questId, ExplorationQuestStatus.TURNED_IN)
        next = next.copy(partyBerries = next.partyBerries + berries)
        return InventoryEngine.grant(next, playerId, itemId, 1)
    }

    private fun requireQuest(world: WorldState, questId: String) {
        require(questId.isNotBlank()) { "Quest id is required" }
        val exists = ExplorationEngine.mapFor(world.campaignId, world.islandId).npcs.values.any { it.questId == questId }
        require(exists) { "Unknown quest $questId on ${world.islandId}" }
    }

    private fun setStatus(
        world: WorldState,
        playerId: String,
        questId: String,
        status: ExplorationQuestStatus,
    ): WorldState = world.copy(
        worldFlags = world.worldFlags + (statusKey(world, playerId, questId) to status.name),
    )

    private fun statusKey(world: WorldState, playerId: String, questId: String): String =
        "quest.${world.islandId}.$playerId.$questId.status"
}
