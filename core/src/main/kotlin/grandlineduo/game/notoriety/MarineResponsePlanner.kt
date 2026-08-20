package grandlineduo.game.notoriety

import grandlineduo.game.powers.DevilFruitCategory

enum class MarineResponseTier(val threatCost: Int, val directorFlag: String?) {
    NONE(0, null),
    PATROL(3, "MARINE_RESPONSE_PATROL"),
    REINFORCED(4, "MARINE_RESPONSE_REINFORCED"),
    CAPTAIN(5, "MARINE_RESPONSE_CAPTAIN"),
    SPECIALIST(7, "MARINE_RESPONSE_SPECIALIST"),
    VICE_ADMIRAL(9, "MARINE_RESPONSE_VICE_ADMIRAL"),
}

data class MarineResponseContext(
    val totalBounty: Long,
    val internalThreatPoints: Int,
    val marinesCanReach: Boolean,
    val exposedHaoshoku: Boolean = false,
    val exposedDevilFruitCategory: DevilFruitCategory? = null,
) {
    init {
        require(totalBounty >= 0) { "Total bounty cannot be negative" }
        require(internalThreatPoints >= 0) { "Internal threat points cannot be negative" }
    }
}

data class MarineResponsePlan(
    val tier: MarineResponseTier,
    val threatCost: Int,
    val directorFlags: Set<String>,
)

object MarineResponsePlanner {
    fun plan(context: MarineResponseContext): MarineResponsePlan {
        if (!context.marinesCanReach) return MarineResponsePlan(MarineResponseTier.NONE, 0, emptySet())

        val bountyScore = when {
            context.totalBounty < 10_000_000L -> 1
            context.totalBounty < 30_000_000L -> 2
            context.totalBounty < 60_000_000L -> 3
            context.totalBounty < 120_000_000L -> 4
            else -> 5
        }
        val intelligenceScore = (context.internalThreatPoints / 20).coerceAtMost(2)
        val powerScore =
            (if (context.exposedHaoshoku) 2 else 0) +
                when (context.exposedDevilFruitCategory) {
                    DevilFruitCategory.LOGIA -> 1
                    DevilFruitCategory.ZOAN, DevilFruitCategory.PARAMECIA, null -> 0
                }
        val score = bountyScore + intelligenceScore + powerScore
        val tier = when {
            score <= 1 -> MarineResponseTier.PATROL
            score <= 3 -> MarineResponseTier.REINFORCED
            score <= 5 -> MarineResponseTier.CAPTAIN
            score <= 7 -> MarineResponseTier.SPECIALIST
            else -> MarineResponseTier.VICE_ADMIRAL
        }
        val flags = MarineResponseTier.entries
            .filter { it != MarineResponseTier.NONE && it.ordinal <= tier.ordinal }
            .mapNotNullTo(linkedSetOf()) { it.directorFlag }
        return MarineResponsePlan(tier, tier.threatCost, flags)
    }
}
