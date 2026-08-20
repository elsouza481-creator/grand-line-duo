package grandlineduo.game.powers

enum class DevilFruitCategory { PARAMECIA, ZOAN, LOGIA }

data class DevilFruitDefinition(
    val id: String,
    val displayName: String,
    val category: DevilFruitCategory,
) {
    init {
        require(id.isNotBlank()) { "Fruit id is required" }
        require(displayName.isNotBlank()) { "Fruit display name is required" }
    }
}

data class DevilFruitState(
    val fruitId: String,
    val category: DevilFruitCategory,
    val revealedName: String? = null,
    val mastery: Int = 0,
    val useCount: Int = 0,
) {
    init {
        require(fruitId.isNotBlank()) { "Fruit id is required" }
        require(mastery in 0..6) { "Devil Fruit mastery must be in 0..6" }
        require(useCount >= 0) { "Devil Fruit use count cannot be negative" }
        require(revealedName == null || revealedName.isNotBlank()) { "Revealed fruit name cannot be blank" }
    }
}

enum class DevilFruitConsumeFailure { ALREADY_HAS_FRUIT }

sealed interface DevilFruitConsumeResult {
    data class Consumed(val state: DevilFruitState) : DevilFruitConsumeResult
    data class Rejected(
        val reason: DevilFruitConsumeFailure,
        val state: DevilFruitState,
    ) : DevilFruitConsumeResult
}

enum class DevilFruitMasteryFailure { INSUFFICIENT_USE, MASTERY_CAP }

sealed interface DevilFruitMasteryResult {
    data class Advanced(val state: DevilFruitState) : DevilFruitMasteryResult
    data class Rejected(
        val reason: DevilFruitMasteryFailure,
        val state: DevilFruitState,
    ) : DevilFruitMasteryResult
}

object DevilFruitEngine {
    fun consume(
        current: DevilFruitState?,
        definition: DevilFruitDefinition,
        identified: Boolean,
    ): DevilFruitConsumeResult {
        if (current != null) {
            return DevilFruitConsumeResult.Rejected(DevilFruitConsumeFailure.ALREADY_HAS_FRUIT, current)
        }
        return DevilFruitConsumeResult.Consumed(
            DevilFruitState(
                fruitId = definition.id,
                category = definition.category,
                revealedName = definition.displayName.takeIf { identified },
                mastery = 0,
                useCount = 0,
            )
        )
    }

    fun revealIdentity(state: DevilFruitState, definition: DevilFruitDefinition): DevilFruitState {
        require(state.fruitId == definition.id) { "Fruit definition does not match consumed fruit" }
        require(state.category == definition.category) { "Fruit category mismatch" }
        return state.copy(revealedName = definition.displayName)
    }

    fun recordUse(state: DevilFruitState): DevilFruitState =
        state.copy(useCount = Math.addExact(state.useCount, 1))

    fun trainMastery(state: DevilFruitState): DevilFruitMasteryResult {
        if (state.mastery >= 6) {
            return DevilFruitMasteryResult.Rejected(DevilFruitMasteryFailure.MASTERY_CAP, state)
        }
        val requiredUses = (state.mastery + 1) * 3
        if (state.useCount < requiredUses) {
            return DevilFruitMasteryResult.Rejected(DevilFruitMasteryFailure.INSUFFICIENT_USE, state)
        }
        return DevilFruitMasteryResult.Advanced(
            state.copy(
                mastery = state.mastery + 1,
                useCount = state.useCount - requiredUses,
            )
        )
    }

    fun canSwim(state: DevilFruitState?): Boolean = state == null

    fun vulnerableToSeastone(state: DevilFruitState?): Boolean = state != null
}
