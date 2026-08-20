package grandlineduo.game.combat

import grandlineduo.game.character.ClassMasteryState
import grandlineduo.game.character.ClassPath

data class ClassCombatBonus(
    val attackBonus: Int = 0,
    val damageReduction: Int = 0,
)

object ClassMasteryCombatResolver {
    fun resolve(mastery: ClassMasteryState?): ClassCombatBonus {
        if (mastery == null) return ClassCombatBonus()

        val path = mastery.primaryClass
        val tier = combatTier(mastery.levelOf(path))
        if (tier == 0) return ClassCombatBonus()

        return when (path) {
            ClassPath.SWORDSMAN,
            ClassPath.GUNNER,
            ClassPath.ROGUE,
            -> ClassCombatBonus(attackBonus = tier)

            ClassPath.BRAWLER -> ClassCombatBonus(
                attackBonus = tier,
                damageReduction = (tier + 1) / 2,
            )

            ClassPath.CAPTAIN -> ClassCombatBonus(
                attackBonus = (tier + 1) / 2,
                damageReduction = (tier + 1) / 2,
            )

            ClassPath.NAVIGATOR,
            ClassPath.DOCTOR,
            ClassPath.SHIPWRIGHT,
            ClassPath.COOK,
            ClassPath.SCHOLAR,
            -> ClassCombatBonus()
        }
    }

    private fun combatTier(level: Int): Int = when {
        level <= 0 -> 0
        level < 10 -> 1
        level < 25 -> 2
        level < 50 -> 3
        else -> 4 + ((level - 50) / 50)
    }
}
