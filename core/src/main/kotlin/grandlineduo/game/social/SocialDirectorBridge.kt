package grandlineduo.game.social

object SocialDirectorBridge {
    const val PRESENT_ALLIED_FACTION = "SOCIAL_PRESENT_ALLIED_FACTION"
    const val PRESENT_HOSTILE_FACTION = "SOCIAL_PRESENT_HOSTILE_FACTION"

    fun flagsFor(social: SocialState, presentFactions: Set<String>): Set<String> = buildSet {
        if (social.npcRelationships.values.any { it.status == NpcStatus.ACTIVE && it.bond == NpcBond.ALLY }) {
            add(SocialWorldFlags.HAS_ALLY)
        }
        if (social.npcRelationships.values.any { it.status == NpcStatus.ACTIVE && it.bond == NpcBond.RIVAL }) {
            add(SocialWorldFlags.HAS_RIVAL)
        }
        if (social.npcRelationships.values.any { it.status == NpcStatus.ACTIVE && it.bond == NpcBond.ENEMY }) {
            add(SocialWorldFlags.HAS_ENEMY)
        }
        if (presentFactions.any { (social.factionStanding[it] ?: 0) >= 60 }) add(PRESENT_ALLIED_FACTION)
        if (presentFactions.any { (social.factionStanding[it] ?: 0) <= -60 }) add(PRESENT_HOSTILE_FACTION)
    }
}
