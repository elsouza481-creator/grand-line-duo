package grandlineduo.game.ship

import grandlineduo.game.character.CharacterProfile
import grandlineduo.game.character.ClassPath

data class VoyageClassBonus(
    val actionScore: Int = 0,
    val hullMitigation: Int = 0,
    val supplyMitigation: Int = 0,
)

object ClassMasteryVoyageResolver {
    fun resolve(profile: CharacterProfile?, action: VoyageAction): VoyageClassBonus {
        val mastery = profile?.classMastery ?: return VoyageClassBonus()
        val path = mastery.primaryClass
        val tier = masteryTier(mastery.levelOf(path))
        if (tier == 0 || !matchesDuty(path, action)) return VoyageClassBonus()

        return when (action) {
            VoyageAction.HELM -> VoyageClassBonus(
                actionScore = tier * 2,
                hullMitigation = tier * 2,
            )
            VoyageAction.LOOKOUT -> VoyageClassBonus(
                actionScore = tier * 2,
                hullMitigation = tier,
                supplyMitigation = tier,
            )
            VoyageAction.CANNONS -> VoyageClassBonus(
                actionScore = tier * 2,
                hullMitigation = tier * 2,
            )
            VoyageAction.REPAIR -> VoyageClassBonus(
                actionScore = tier * 2,
                hullMitigation = tier * 3,
            )
            VoyageAction.PROTECT_SUPPLIES -> VoyageClassBonus(
                actionScore = tier * 2,
                supplyMitigation = tier * 3,
            )
        }
    }

    private fun matchesDuty(path: ClassPath, action: VoyageAction): Boolean = when (action) {
        VoyageAction.HELM, VoyageAction.LOOKOUT -> path == ClassPath.NAVIGATOR
        VoyageAction.CANNONS -> path == ClassPath.GUNNER
        VoyageAction.REPAIR -> path == ClassPath.SHIPWRIGHT
        VoyageAction.PROTECT_SUPPLIES -> path == ClassPath.COOK
    }

    private fun masteryTier(level: Int): Int = when {
        level <= 0 -> 0
        level < 10 -> 1
        level < 25 -> 2
        level < 50 -> 3
        else -> 4 + ((level - 50) / 50)
    }
}
