package grandlineduo.game.quest

import grandlineduo.core.model.WorldState

data class QuestFieldLastResult(
    val actorId: String,
    val actionType: QuestFieldActionType,
    val checkId: String,
    val roll: Int,
    val modifier: Int,
    val total: Int,
    val difficultyClass: Int,
    val success: Boolean,
)

object QuestFieldState {
    private fun attemptKey(id: String) = "quest.field.attempt.$id"
    private fun actorKey(id: String) = "quest.field.last.actor.$id"
    private fun actionKey(id: String) = "quest.field.last.action.$id"
    private fun checkKey(id: String) = "quest.field.last.check.$id"
    private fun rollKey(id: String) = "quest.field.last.roll.$id"
    private fun modifierKey(id: String) = "quest.field.last.modifier.$id"
    private fun totalKey(id: String) = "quest.field.last.total.$id"
    private fun dcKey(id: String) = "quest.field.last.dc.$id"
    private fun successKey(id: String) = "quest.field.last.success.$id"

    fun attemptCount(world: WorldState, questId: String): Int =
        world.worldFlags[attemptKey(questId)]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    fun readLast(world: WorldState, questId: String): QuestFieldLastResult? {
        val flags = world.worldFlags
        val actorId = flags[actorKey(questId)] ?: return null
        val actionType = flags[actionKey(questId)]?.let { runCatching { QuestFieldActionType.valueOf(it) }.getOrNull() }
            ?: return null
        val checkId = flags[checkKey(questId)] ?: return null
        val roll = flags[rollKey(questId)]?.toIntOrNull() ?: return null
        val modifier = flags[modifierKey(questId)]?.toIntOrNull() ?: return null
        val total = flags[totalKey(questId)]?.toIntOrNull() ?: return null
        val difficultyClass = flags[dcKey(questId)]?.toIntOrNull() ?: return null
        val success = flags[successKey(questId)]?.toBooleanStrictOrNull() ?: return null
        return QuestFieldLastResult(
            actorId = actorId,
            actionType = actionType,
            checkId = checkId,
            roll = roll,
            modifier = modifier,
            total = total,
            difficultyClass = difficultyClass,
            success = success,
        )
    }

    fun writeAttemptResult(
        world: WorldState,
        questId: String,
        result: QuestFieldAttemptResult,
    ): WorldState {
        require(questId == result.questId) { "Field result quest id mismatch" }
        require(result.attemptOrdinal > 0) { "Field attempt ordinal must be positive" }
        val flags = world.worldFlags.toMutableMap().apply {
            put(attemptKey(questId), result.attemptOrdinal.toString())
            put(actorKey(questId), result.actorId)
            put(actionKey(questId), result.actionType.name)
            put(checkKey(questId), result.checkId)
            put(rollKey(questId), result.roll.toString())
            put(modifierKey(questId), result.modifier.toString())
            put(totalKey(questId), result.total.toString())
            put(dcKey(questId), result.difficultyClass.toString())
            put(successKey(questId), result.success.toString())
        }
        return world.copy(worldFlags = flags)
    }

    fun clear(world: WorldState, questId: String): WorldState {
        val flags = world.worldFlags.toMutableMap().apply {
            remove(attemptKey(questId))
            remove(actorKey(questId))
            remove(actionKey(questId))
            remove(checkKey(questId))
            remove(rollKey(questId))
            remove(modifierKey(questId))
            remove(totalKey(questId))
            remove(dcKey(questId))
            remove(successKey(questId))
        }
        return if (flags == world.worldFlags) world else world.copy(worldFlags = flags)
    }
}
