package grandlineduo.game.notoriety

enum class IncidentVisibility { SECRET, RUMORED, CONFIRMED }

enum class BountyIncidentType(
    internal val confirmedBaseBounty: Long,
    internal val threatWeight: Int,
) {
    DEFEATED_MARINE_OFFICER(2_000_000L, 3),
    MARINE_BASE_DESTROYED(4_000_000L, 5),
    GOVERNMENT_SECRET_EXPOSED(6_000_000L, 8),
    ASSAULTED_MARINES(1_000_000L, 2),
    DEFEATED_PIRATE_RIVAL(500_000L, 1),
}

data class BountyIncident(
    val type: BountyIncidentType,
    val severity: Int,
    val visibility: IncidentVisibility,
) {
    init {
        require(severity in 1..5) { "Severity must be between 1 and 5" }
    }
}

data class BountyAssessment(
    val delta: Long,
    val newBounty: Long,
    val internalThreatPoints: Int,
)

object BountyEngine {
    const val MAX_WORLD_BOUNTY: Long = 10_000_000_000L

    fun assess(currentBounty: Long, incident: BountyIncident): BountyAssessment {
        require(currentBounty in 0..MAX_WORLD_BOUNTY) { "Current bounty outside world bounds" }

        val confirmedValue = incident.type.confirmedBaseBounty * incident.severity.toLong()
        val publicCandidate = when (incident.visibility) {
            IncidentVisibility.SECRET -> 0L
            IncidentVisibility.RUMORED -> confirmedValue * 35L / 100L
            IncidentVisibility.CONFIRMED -> confirmedValue
        }
        val room = MAX_WORLD_BOUNTY - currentBounty
        val delta = minOf(publicCandidate, room)
        val visibilityThreatMultiplier = when (incident.visibility) {
            IncidentVisibility.SECRET -> 3
            IncidentVisibility.RUMORED -> 2
            IncidentVisibility.CONFIRMED -> 2
        }
        val internalThreatPoints = incident.type.threatWeight * incident.severity * visibilityThreatMultiplier

        return BountyAssessment(
            delta = delta,
            newBounty = currentBounty + delta,
            internalThreatPoints = internalThreatPoints,
        )
    }
}
