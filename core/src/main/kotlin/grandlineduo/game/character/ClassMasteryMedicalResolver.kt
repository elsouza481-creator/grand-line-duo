package grandlineduo.game.character

object ClassMasteryMedicalResolver {
    fun healingBonus(profile: CharacterProfile?, baseHealing: Int): Int {
        if (baseHealing <= 0) return 0
        val mastery = profile?.classMastery ?: return 0
        if (mastery.primaryClass != ClassPath.DOCTOR) return 0
        val tier = masteryTier(mastery.levelOf(ClassPath.DOCTOR))
        return tier * 4
    }

    private fun masteryTier(level: Int): Int = when {
        level <= 0 -> 0
        level < 10 -> 1
        level < 25 -> 2
        level < 50 -> 3
        else -> 4 + ((level - 50) / 50)
    }
}
