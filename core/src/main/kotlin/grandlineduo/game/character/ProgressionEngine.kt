package grandlineduo.game.character

enum class ProgressionError {
    MISSING_TRAINING,
    INSUFFICIENT_PEV,
    ATTRIBUTE_CAP,
    SKILL_CAP,
}

sealed interface ProgressionResult {
    val profile: CharacterProfile

    data class Success(override val profile: CharacterProfile) : ProgressionResult
    data class Rejected(
        val error: ProgressionError,
        override val profile: CharacterProfile,
    ) : ProgressionResult
}

object ProgressionEngine {
    private const val ATTRIBUTE_COST = 3
    private const val SKILL_COST = 2
    private const val ATTRIBUTE_CAP = 5
    private const val SKILL_CAP = 5

    fun awardEvolutionPoints(profile: CharacterProfile, amount: Int): CharacterProfile {
        require(amount > 0) { "Evolution point award must be positive" }
        return profile.copy(evolutionPoints = Math.addExact(profile.evolutionPoints, amount))
    }

    fun markAttributeTraining(profile: CharacterProfile, attribute: Attribute): CharacterProfile =
        profile.copy(trainingMarks = profile.trainingMarks + attributeMark(attribute))

    fun markSkillTraining(profile: CharacterProfile, skill: Skill): CharacterProfile =
        profile.copy(trainingMarks = profile.trainingMarks + skillMark(skill))

    fun increaseAttribute(profile: CharacterProfile, attribute: Attribute): ProgressionResult {
        val current = profile.attributes.getValue(attribute)
        if (current >= ATTRIBUTE_CAP) return ProgressionResult.Rejected(ProgressionError.ATTRIBUTE_CAP, profile)

        val mark = attributeMark(attribute)
        if (mark !in profile.trainingMarks) {
            return ProgressionResult.Rejected(ProgressionError.MISSING_TRAINING, profile)
        }
        if (profile.evolutionPoints < ATTRIBUTE_COST) {
            return ProgressionResult.Rejected(ProgressionError.INSUFFICIENT_PEV, profile)
        }

        return ProgressionResult.Success(
            profile.copy(
                attributes = profile.attributes + (attribute to current + 1),
                evolutionPoints = profile.evolutionPoints - ATTRIBUTE_COST,
                trainingMarks = profile.trainingMarks - mark,
            )
        )
    }

    fun increaseSkill(profile: CharacterProfile, skill: Skill): ProgressionResult {
        val current = profile.skills[skill] ?: 0
        if (current >= SKILL_CAP) return ProgressionResult.Rejected(ProgressionError.SKILL_CAP, profile)

        val mark = skillMark(skill)
        if (mark !in profile.trainingMarks) {
            return ProgressionResult.Rejected(ProgressionError.MISSING_TRAINING, profile)
        }
        if (profile.evolutionPoints < SKILL_COST) {
            return ProgressionResult.Rejected(ProgressionError.INSUFFICIENT_PEV, profile)
        }

        return ProgressionResult.Success(
            profile.copy(
                skills = profile.skills + (skill to current + 1),
                evolutionPoints = profile.evolutionPoints - SKILL_COST,
                trainingMarks = profile.trainingMarks - mark,
            )
        )
    }

    private fun attributeMark(attribute: Attribute) = "attribute:${attribute.name}"
    private fun skillMark(skill: Skill) = "skill:${skill.name}"
}
