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
        val objective = ExplorationEngine.questObjectiveAt(world, playerId)
            ?: throw IllegalArgumentException("Player must stand on the quest objective tile")
        require(objective.questId == questId) { "This objective does not belong to $questId" }
        return setStatus(world, playerId, questId, ExplorationQuestStatus.OBJECTIVE_COMPLETE)
    }

    fun turnIn(world: WorldState, playerId: String, questId: String): WorldState {
        require(status(world, playerId, questId) == ExplorationQuestStatus.OBJECTIVE_COMPLETE) { "Quest objective is not complete" }
        val npc = ExplorationEngine.npcAt(world, playerId)
            ?: throw IllegalArgumentException("Player must return to the quest giver")
        require(npc.questId == questId) { "This NPC cannot complete $questId" }

        var next = setStatus(world, playerId, questId, ExplorationQuestStatus.TURNED_IN)
        next = next.copy(partyBerries = next.partyBerries + REWARD_BERRIES)
        return InventoryEngine.grant(next, playerId, REWARD_ITEM_ID, 1)
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
