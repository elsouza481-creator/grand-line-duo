package grandlineduo.game.crew

object CrewDirectorBridge {
    const val HAS_DOCTOR = "CREW_HAS_DOCTOR"
    const val HAS_CARPENTER = "CREW_HAS_CARPENTER"
    const val HAS_NAVIGATOR = "CREW_HAS_NAVIGATOR"
    const val LOW_LOYALTY = "CREW_LOW_LOYALTY"
    const val MEMBER_CAPTURED = "CREW_MEMBER_CAPTURED"
    const val MEMBER_MISSING = "CREW_MEMBER_MISSING"

    fun flagsFor(crew: CrewState): Set<String> = buildSet {
        if (CrewEngine.bestCompetence(crew, CrewRole.DOCTOR) > 0) add(HAS_DOCTOR)
        if (CrewEngine.bestCompetence(crew, CrewRole.CARPENTER) > 0) add(HAS_CARPENTER)
        if (CrewEngine.bestCompetence(crew, CrewRole.NAVIGATOR) > 0) add(HAS_NAVIGATOR)
        if (crew.members.values.any { it.status in setOf(CrewStatus.ACTIVE, CrewStatus.WOUNDED) && it.loyalty <= -40 }) {
            add(LOW_LOYALTY)
        }
        if (crew.members.values.any { it.status == CrewStatus.CAPTURED }) add(MEMBER_CAPTURED)
        if (crew.members.values.any { it.status == CrewStatus.MISSING }) add(MEMBER_MISSING)
    }
}
