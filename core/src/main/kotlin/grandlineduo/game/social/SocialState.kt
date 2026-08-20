package grandlineduo.game.social

enum class NpcBond { ENEMY, RIVAL, NEUTRAL, ACQUAINTANCE, ALLY }
enum class NpcStatus { ACTIVE, MISSING, CAPTURED, DEAD }

data class NpcRelationship(
    val affinity: Int = 0,
    val bond: NpcBond = NpcBond.NEUTRAL,
    val status: NpcStatus = NpcStatus.ACTIVE,
) {
    init {
        require(affinity in -100..100) { "NPC affinity must be in -100..100" }
    }
}

data class SocialState(
    val factionStanding: Map<String, Int> = emptyMap(),
    val npcRelationships: Map<String, NpcRelationship> = emptyMap(),
) {
    init {
        require(factionStanding.keys.none(String::isBlank)) { "Faction id cannot be blank" }
        require(factionStanding.values.all { it in -100..100 }) { "Faction standing must be in -100..100" }
        require(npcRelationships.keys.none(String::isBlank)) { "NPC id cannot be blank" }
    }
}

enum class SocialIncidentType {
    HELPED_NPC,
    BETRAYED_NPC,
    SAVED_SETTLEMENT,
    ATTACKED_FACTION,
    KEPT_PROMISE,
    BROKE_PROMISE,
    NPC_CAPTURED,
    NPC_MISSING,
    NPC_RETURNED,
    NPC_DIED,
}

data class SocialIncident(
    val type: SocialIncidentType,
    val npcId: String? = null,
    val factionId: String? = null,
) {
    init {
        require(npcId == null || npcId.isNotBlank()) { "NPC id cannot be blank" }
        require(factionId == null || factionId.isNotBlank()) { "Faction id cannot be blank" }
        when (type) {
            SocialIncidentType.HELPED_NPC,
            SocialIncidentType.BETRAYED_NPC,
            SocialIncidentType.KEPT_PROMISE,
            SocialIncidentType.BROKE_PROMISE,
            SocialIncidentType.NPC_CAPTURED,
            SocialIncidentType.NPC_MISSING,
            SocialIncidentType.NPC_RETURNED,
            SocialIncidentType.NPC_DIED -> require(npcId != null) { "$type requires npcId" }
            SocialIncidentType.SAVED_SETTLEMENT,
            SocialIncidentType.ATTACKED_FACTION -> require(factionId != null) { "$type requires factionId" }
        }
    }
}
