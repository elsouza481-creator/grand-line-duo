package grandlineduo.game.crew

enum class CrewRole {
    NAVIGATOR,
    DOCTOR,
    COOK,
    CARPENTER,
    GUNNER,
    LOOKOUT,
}

enum class CrewStatus {
    ACTIVE,
    WOUNDED,
    CAPTURED,
    MISSING,
    DESERTED,
    DEAD,
}

data class CrewMemberState(
    val npcId: String,
    val name: String,
    val role: CrewRole,
    val competence: Int,
    val loyalty: Int = 0,
    val injurySeverity: Int = 0,
    val status: CrewStatus = CrewStatus.ACTIVE,
    val playerAffinity: Map<String, Int> = emptyMap(),
) {
    init {
        require(npcId.isNotBlank()) { "Crew NPC id is required" }
        require(name.isNotBlank()) { "Crew name is required" }
        require(competence in 1..5) { "Crew competence must be in 1..5" }
        require(loyalty in -100..100) { "Crew loyalty must be in -100..100" }
        require(injurySeverity in 0..3) { "Crew injury severity must be in 0..3" }
        require(playerAffinity.keys.all { it in HUMAN_PLAYER_IDS }) { "Crew affinity player must be p1, p2, p3 or p4" }
        require(playerAffinity.values.all { it in -100..100 }) { "Crew player affinity must be in -100..100" }
        if (status == CrewStatus.ACTIVE) require(injurySeverity == 0) { "Active crew cannot carry an untreated injury" }
        if (status == CrewStatus.WOUNDED) require(injurySeverity > 0) { "Wounded crew requires injury severity" }
    }

    private companion object {
        val HUMAN_PLAYER_IDS = setOf("p1", "p2", "p3", "p4")
    }
}

data class CrewState(
    val members: Map<String, CrewMemberState> = emptyMap(),
) {
    init {
        require(members.keys.none(String::isBlank)) { "Crew member key cannot be blank" }
        require(members.all { (id, member) -> id == member.npcId }) { "Crew member key must match NPC id" }
    }
}
