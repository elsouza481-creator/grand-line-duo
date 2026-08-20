package grandlineduo.game.character

enum class ClassPath {
    SWORDSMAN,
    BRAWLER,
    GUNNER,
    NAVIGATOR,
    DOCTOR,
    SHIPWRIGHT,
    COOK,
    ROGUE,
    SCHOLAR,
    CAPTAIN,
}

enum class ClassPerk {
    CLASS_INITIATE,
    SPECIALIST,
    VETERAN,
    MASTER,
}

data class ClassMasteryState(
    val primaryClass: ClassPath,
    val levels: Map<ClassPath, Int> = emptyMap(),
    val experience: Map<ClassPath, Long> = emptyMap(),
) {
    fun levelOf(path: ClassPath): Int = levels[path] ?: 0
    fun experienceOf(path: ClassPath): Long = experience[path] ?: 0L
}

object ClassMasteryEngine {
    private const val BASE_EXPERIENCE = 100L
    private const val EXPERIENCE_GROWTH_PER_LEVEL = 25L

    fun start(primaryClass: ClassPath): ClassMasteryState =
        ClassMasteryState(primaryClass = primaryClass)

    fun train(
        state: ClassMasteryState,
        path: ClassPath,
        effort: Int,
    ): ClassMasteryState = train(state, path, effort.toLong())

    fun train(
        state: ClassMasteryState,
        path: ClassPath,
        effort: Long,
    ): ClassMasteryState {
        require(effort > 0L) { "Class mastery effort must be positive" }

        var level = state.levelOf(path)
        var experience = Math.addExact(state.experienceOf(path), effort)

        while (experience >= experienceRequiredForLevel(level)) {
            experience -= experienceRequiredForLevel(level)
            level = Math.addExact(level, 1)
        }

        return state.copy(
            levels = state.levels + (path to level),
            experience = state.experience + (path to experience),
        )
    }

    fun experienceRequiredForLevel(level: Int): Long {
        require(level >= 0) { "Class mastery level cannot be negative" }
        return Math.addExact(BASE_EXPERIENCE, Math.multiplyExact(level.toLong(), EXPERIENCE_GROWTH_PER_LEVEL))
    }

    fun unlockedPerks(path: ClassPath, level: Int): Set<ClassPerk> {
        require(level >= 0) { "Class mastery level cannot be negative" }
        @Suppress("UNUSED_VARIABLE")
        val classPath = path

        return buildSet {
            if (level >= 1) add(ClassPerk.CLASS_INITIATE)
            if (level >= 10) add(ClassPerk.SPECIALIST)
            if (level >= 25) add(ClassPerk.VETERAN)
            if (level >= 50) add(ClassPerk.MASTER)
        }
    }
}
