package grandlineduo.game.crew

object CrewRecruitmentCatalog {
    private val byIsland = mapOf(
        "stormglass-cay" to listOf(CrewMemberState("mara-tide", "Mara Tide", CrewRole.NAVIGATOR, competence = 2, loyalty = 15)),
        "emberwake" to listOf(CrewMemberState("koro-sparks", "Koro Sparks", CrewRole.CARPENTER, competence = 3, loyalty = 5)),
        "brineveil" to listOf(CrewMemberState("sena-whitecap", "Sena Whitecap", CrewRole.DOCTOR, competence = 3, loyalty = 10)),
        "gearfall" to listOf(CrewMemberState("brass-jo", "Brass Jo", CrewRole.GUNNER, competence = 3, loyalty = 0)),
        "hollow-crown" to listOf(CrewMemberState("milo-eye", "Milo Eye", CrewRole.LOOKOUT, competence = 4, loyalty = -5)),
        "meridian-vault" to listOf(CrewMemberState("orsa-pan", "Orsa Pan", CrewRole.COOK, competence = 4, loyalty = 20)),
    )

    fun candidates(islandId: String): List<CrewMemberState> = byIsland[islandId].orEmpty()

    fun requireCandidate(islandId: String, npcId: String): CrewMemberState =
        candidates(islandId).firstOrNull { it.npcId == npcId }
            ?: throw IllegalArgumentException("Crew candidate is not available on this island")
}
