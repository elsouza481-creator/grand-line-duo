package grandlineduo.game.social

object SocialConsequenceEngine {
    fun apply(state: SocialState, incident: SocialIncident): SocialState {
        val npcId = incident.npcId
        val currentRelationship = npcId?.let { state.npcRelationships[it] }
        if (currentRelationship?.status == NpcStatus.DEAD) return state

        val statusChange = when (incident.type) {
            SocialIncidentType.NPC_CAPTURED -> NpcStatus.CAPTURED
            SocialIncidentType.NPC_MISSING -> NpcStatus.MISSING
            SocialIncidentType.NPC_RETURNED -> NpcStatus.ACTIVE
            SocialIncidentType.NPC_DIED -> NpcStatus.DEAD
            else -> null
        }
        if (statusChange != null) {
            val current = currentRelationship ?: NpcRelationship()
            return state.copy(
                npcRelationships = state.npcRelationships + (npcId!! to current.copy(status = statusChange)),
            )
        }

        val (npcDelta, factionDelta) = when (incident.type) {
            SocialIncidentType.HELPED_NPC -> 20 to 5
            SocialIncidentType.BETRAYED_NPC -> -80 to -10
            SocialIncidentType.SAVED_SETTLEMENT -> 0 to 30
            SocialIncidentType.ATTACKED_FACTION -> 0 to -40
            SocialIncidentType.KEPT_PROMISE -> 15 to 5
            SocialIncidentType.BROKE_PROMISE -> -25 to -5
            SocialIncidentType.NPC_CAPTURED,
            SocialIncidentType.NPC_MISSING,
            SocialIncidentType.NPC_RETURNED,
            SocialIncidentType.NPC_DIED -> error("Status incident should have returned earlier")
        }

        var next = state
        if (npcId != null && npcDelta != 0) {
            val current = state.npcRelationships[npcId] ?: NpcRelationship()
            val affinity = (current.affinity + npcDelta).coerceIn(-100, 100)
            next = next.copy(
                npcRelationships = next.npcRelationships + (
                    npcId to current.copy(affinity = affinity, bond = bondFor(affinity))
                ),
            )
        }
        val factionId = incident.factionId
        if (factionId != null && factionDelta != 0) {
            val current = next.factionStanding[factionId] ?: 0
            next = next.copy(
                factionStanding = next.factionStanding + (factionId to (current + factionDelta).coerceIn(-100, 100)),
            )
        }
        return next
    }

    fun bondFor(affinity: Int): NpcBond {
        require(affinity in -100..100) { "NPC affinity must be in -100..100" }
        return when {
            affinity <= -60 -> NpcBond.ENEMY
            affinity <= -20 -> NpcBond.RIVAL
            affinity >= 60 -> NpcBond.ALLY
            affinity >= 20 -> NpcBond.ACQUAINTANCE
            else -> NpcBond.NEUTRAL
        }
    }
}
