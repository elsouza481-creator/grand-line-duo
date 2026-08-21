package grandlineduo.game.arc

import grandlineduo.game.character.CharacterProfile
import grandlineduo.game.character.ClassPath

object ClassMasteryArcResolver {
    private const val SCHOLAR_FLAG_PREFIX = "SCHOLAR_ANALYSIS_TIER:"
    private val investigationChoices = setOf(
        "question_contacts",
        "force_information",
        "reveal_intel",
        "keep_intel",
        "scout_target",
    )

    fun scholarTierForChoice(profile: CharacterProfile?, phase: ArcPhase, choiceId: String): Int {
        if (phase != ArcPhase.INVESTIGATION || choiceId !in investigationChoices) return 0
        val mastery = profile?.classMastery ?: return 0
        if (mastery.primaryClass != ClassPath.SCHOLAR) return 0
        return masteryTier(mastery.levelOf(ClassPath.SCHOLAR))
    }

    fun applyScholarAnalysis(state: ArcState, tier: Int): ArcState {
        if (tier <= 0) return state
        val strongest = maxOf(tier, scholarAnalysisTier(state.sharedFlags))
        val cleaned = state.sharedFlags.filterNot { it.startsWith(SCHOLAR_FLAG_PREFIX) }.toSet()
        return state.copy(sharedFlags = cleaned + "$SCHOLAR_FLAG_PREFIX$strongest")
    }

    fun scholarAnalysisTier(flags: Set<String>): Int = flags.asSequence()
        .filter { it.startsWith(SCHOLAR_FLAG_PREFIX) }
        .mapNotNull { it.removePrefix(SCHOLAR_FLAG_PREFIX).toIntOrNull() }
        .filter { it > 0 }
        .maxOrNull()
        ?: 0

    private fun masteryTier(level: Int): Int = when {
        level <= 0 -> 0
        level < 10 -> 1
        level < 25 -> 2
        level < 50 -> 3
        else -> 4 + ((level - 50) / 50)
    }
}
