package grandlineduo.game.quest

import grandlineduo.core.model.WorldState

enum class QuestObjectiveEventType {
    ENEMY_DEFEATED,
    ISLAND_VISITED,
    ITEM_ACQUIRED,
    NPC_RESCUED,
    ESCORT_ARRIVED,
    CLUE_DISCOVERED,
}

data class QuestObjectiveEvent(
    val type: QuestObjectiveEventType,
    val targetId: String,
    val islandId: String,
    val amount: Int = 1,
    val sourceQuestId: String? = null,
) {
    init {
        require(targetId.isNotBlank()) { "Quest objective target cannot be blank" }
        require(islandId.isNotBlank()) { "Quest objective island cannot be blank" }
        require(amount > 0) { "Quest objective amount must be positive" }
        sourceQuestId?.let { require(it.isNotBlank()) { "Quest objective source quest cannot be blank" } }
    }
}

object QuestObjectiveRouter {
    fun apply(world: WorldState, event: QuestObjectiveEvent): WorldState {
        if (event.type != QuestObjectiveEventType.ENEMY_DEFEATED) return world

        var next = world
        world.questBoard.active.toSortedMap().forEach { (questId, progress) ->
            val matches = progress.status == QuestStatus.ACTIVE &&
                progress.definition.type == QuestType.HUNT &&
                progress.definition.islandId == event.islandId &&
                progress.definition.targetId == event.targetId &&
                (event.sourceQuestId == null || event.sourceQuestId == questId)
            if (matches) {
                next = QuestEngine.progressObjective(next, questId, event.amount)
            }
        }
        return next
    }
}
