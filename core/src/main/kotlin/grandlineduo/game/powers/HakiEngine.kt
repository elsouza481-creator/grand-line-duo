package grandlineduo.game.powers

import grandlineduo.game.character.Attribute
import grandlineduo.game.character.CharacterProfile

enum class HakiType { KENBUNSHOKU, BUSOSHOKU, HAOSHOKU }

enum class HakiTrigger { TRAINING, TRAUMA, NECESSITY, EXTREME_WILL }

data class HakiDiscipline(
    val mastery: Int,
    val useCount: Int = 0,
) {
    init {
        require(mastery in 1..6) { "Haki mastery must be in 1..6" }
        require(useCount >= 0) { "Haki use count cannot be negative" }
    }
}

data class HakiState(
    val latentHaoshoku: Boolean = false,
    val disciplines: Map<HakiType, HakiDiscipline> = emptyMap(),
)

enum class HakiAwakeningFailure {
    ALREADY_AWAKENED,
    INSUFFICIENT_APTITUDE,
    INTENSITY_TOO_LOW,
    NO_HAOSHOKU_POTENTIAL,
    TRIGGER_NOT_EXTREME,
}

sealed interface HakiAwakeningResult {
    data class Awakened(val state: HakiState) : HakiAwakeningResult
    data class Rejected(val reason: HakiAwakeningFailure, val state: HakiState) : HakiAwakeningResult
}

enum class HakiMasteryFailure {
    NOT_AWAKENED,
    INSUFFICIENT_USE,
    MASTERY_CAP,
}

sealed interface HakiMasteryResult {
    data class Advanced(val state: HakiState) : HakiMasteryResult
    data class Rejected(val reason: HakiMasteryFailure, val state: HakiState) : HakiMasteryResult
}

object HakiEngine {
    fun attemptAwakening(
        profile: CharacterProfile,
        state: HakiState,
        type: HakiType,
        trigger: HakiTrigger,
        intensity: Int,
    ): HakiAwakeningResult {
        require(intensity in 1..5) { "Haki awakening intensity must be in 1..5" }
        if (type in state.disciplines) {
            return HakiAwakeningResult.Rejected(HakiAwakeningFailure.ALREADY_AWAKENED, state)
        }

        if (type == HakiType.HAOSHOKU) {
            if (!state.latentHaoshoku) {
                return HakiAwakeningResult.Rejected(HakiAwakeningFailure.NO_HAOSHOKU_POTENTIAL, state)
            }
            if (trigger !in setOf(HakiTrigger.TRAUMA, HakiTrigger.NECESSITY, HakiTrigger.EXTREME_WILL)) {
                return HakiAwakeningResult.Rejected(HakiAwakeningFailure.TRIGGER_NOT_EXTREME, state)
            }
            if (intensity < 4) {
                return HakiAwakeningResult.Rejected(HakiAwakeningFailure.INTENSITY_TOO_LOW, state)
            }
            return awakened(state, type)
        }

        if (intensity < 2) {
            return HakiAwakeningResult.Rejected(HakiAwakeningFailure.INTENSITY_TOO_LOW, state)
        }
        if (!hasAptitude(profile, type)) {
            return HakiAwakeningResult.Rejected(HakiAwakeningFailure.INSUFFICIENT_APTITUDE, state)
        }
        return awakened(state, type)
    }

    fun recordUse(state: HakiState, type: HakiType): HakiState {
        val discipline = state.disciplines[type]
            ?: throw IllegalArgumentException("$type is not awakened")
        return state.copy(
            disciplines = state.disciplines + (
                type to discipline.copy(useCount = Math.addExact(discipline.useCount, 1))
            ),
        )
    }

    fun trainMastery(state: HakiState, type: HakiType): HakiMasteryResult {
        val discipline = state.disciplines[type]
            ?: return HakiMasteryResult.Rejected(HakiMasteryFailure.NOT_AWAKENED, state)
        if (discipline.mastery >= 6) {
            return HakiMasteryResult.Rejected(HakiMasteryFailure.MASTERY_CAP, state)
        }
        val requiredUses = discipline.mastery * 3
        if (discipline.useCount < requiredUses) {
            return HakiMasteryResult.Rejected(HakiMasteryFailure.INSUFFICIENT_USE, state)
        }
        val advanced = discipline.copy(
            mastery = discipline.mastery + 1,
            useCount = discipline.useCount - requiredUses,
        )
        return HakiMasteryResult.Advanced(
            state.copy(disciplines = state.disciplines + (type to advanced))
        )
    }

    private fun awakened(state: HakiState, type: HakiType): HakiAwakeningResult.Awakened =
        HakiAwakeningResult.Awakened(
            state.copy(disciplines = state.disciplines + (type to HakiDiscipline(mastery = 1)))
        )

    private fun hasAptitude(profile: CharacterProfile, type: HakiType): Boolean = when (type) {
        HakiType.KENBUNSHOKU ->
            profile.attributes.getValue(Attribute.PER) >= 2 || profile.attributes.getValue(Attribute.VON) >= 3
        HakiType.BUSOSHOKU ->
            profile.attributes.getValue(Attribute.CON) >= 2 ||
                profile.attributes.getValue(Attribute.FOR) >= 2 ||
                profile.attributes.getValue(Attribute.VON) >= 3
        HakiType.HAOSHOKU -> false
    }
}
