package grandlineduo.game.quest

import grandlineduo.core.model.WorldState
import grandlineduo.game.character.Attribute
import grandlineduo.game.character.Skill
import java.util.Random

enum class QuestFieldActionType {
    EXPLORE_SITE,
    SEARCH_SUPPLIES,
}

data class QuestFieldAttemptResult(
    val actionType: QuestFieldActionType,
    val questId: String,
    val targetId: String,
    val islandId: String,
    val actorId: String,
    val attemptOrdinal: Int,
    val checkId: String,
    val roll: Int,
    val modifier: Int,
    val total: Int,
    val difficultyClass: Int,
    val success: Boolean,
    val objectiveEventType: QuestObjectiveEventType,
    val progressAmount: Int,
)

object QuestFieldResolver {
    fun difficultyClass(rarity: QuestRarity): Int = when (rarity) {
        QuestRarity.COMMON -> 10
        QuestRarity.RARE -> 12
        QuestRarity.EPIC -> 15
        QuestRarity.LEGENDARY -> 18
    }

    fun progressPerSuccess(rarity: QuestRarity): Int = rarity.ordinal + 1

    fun rollSeed(
        progress: QuestProgress,
        actorId: String,
        actionType: QuestFieldActionType,
        attemptOrdinal: Int,
        campaignSeed: Long,
    ): Long {
        require(attemptOrdinal > 0) { "Field attempt ordinal must be positive" }
        val quest = progress.definition
        return campaignSeed xor
            (quest.questId.hashCode().toLong() * 6364136223846793005L) xor
            (quest.targetId.hashCode().toLong() * -7046029254386353131L) xor
            (quest.rarity.ordinal.toLong() shl 41) xor
            (actionType.ordinal.toLong() shl 33) xor
            (actorId.hashCode().toLong() * 104729L) xor
            (attemptOrdinal.toLong() * 15485863L)
    }

    fun resolve(
        world: WorldState,
        progress: QuestProgress,
        actorId: String,
        actionType: QuestFieldActionType,
        attemptOrdinal: Int,
        campaignSeed: Long,
    ): QuestFieldAttemptResult {
        require(progress.status == QuestStatus.ACTIVE) { "Field quest is not active" }
        require(progress.definition.islandId == world.islandId) { "Field quest is not on the current island" }
        val actor = world.players[actorId] ?: throw IllegalArgumentException("Unknown player $actorId")
        val profile = actor.profile ?: throw IllegalArgumentException("Player profile is incomplete")

        val expectedType = when (actionType) {
            QuestFieldActionType.EXPLORE_SITE -> QuestType.EXPLORE
            QuestFieldActionType.SEARCH_SUPPLIES -> QuestType.COLLECT
        }
        require(progress.definition.type == expectedType) {
            "${actionType.name} does not match ${progress.definition.type.name} contract"
        }

        val per = profile.attributes.getValue(Attribute.PER)
        val intelligence = profile.attributes.getValue(Attribute.INT)
        fun rank(skill: Skill): Int = profile.skills[skill] ?: 0

        val candidates = when (actionType) {
            QuestFieldActionType.EXPLORE_SITE -> listOf(
                "PER + PERCEPTION" to (per + rank(Skill.PERCEPTION)),
                "PER + SURVIVAL" to (per + rank(Skill.SURVIVAL)),
                "PER + INVESTIGATION" to (per + rank(Skill.INVESTIGATION)),
            )
            QuestFieldActionType.SEARCH_SUPPLIES -> listOf(
                "PER + SURVIVAL" to (per + rank(Skill.SURVIVAL)),
                "INT + MEDICINE" to (intelligence + rank(Skill.MEDICINE)),
                "INT + INVESTIGATION" to (intelligence + rank(Skill.INVESTIGATION)),
            )
        }
        val bestModifier = candidates.maxOf { it.second }
        val best = candidates.first { it.second == bestModifier }
        val roll = Random(rollSeed(progress, actorId, actionType, attemptOrdinal, campaignSeed)).nextInt(20) + 1
        val dc = difficultyClass(progress.definition.rarity)
        val total = roll + best.second
        val objectiveType = when (actionType) {
            QuestFieldActionType.EXPLORE_SITE -> QuestObjectiveEventType.LOCATION_VISITED
            QuestFieldActionType.SEARCH_SUPPLIES -> QuestObjectiveEventType.ITEM_ACQUIRED
        }

        return QuestFieldAttemptResult(
            actionType = actionType,
            questId = progress.definition.questId,
            targetId = progress.definition.targetId,
            islandId = progress.definition.islandId,
            actorId = actorId,
            attemptOrdinal = attemptOrdinal,
            checkId = best.first,
            roll = roll,
            modifier = best.second,
            total = total,
            difficultyClass = dc,
            success = total >= dc,
            objectiveEventType = objectiveType,
            progressAmount = progressPerSuccess(progress.definition.rarity),
        )
    }
}
