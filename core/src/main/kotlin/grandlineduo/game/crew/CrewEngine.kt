package grandlineduo.game.crew

import grandlineduo.game.ship.ShipCompartment
import grandlineduo.game.ship.ShipState

object CrewEngine {
    fun capacityFor(ship: ShipState): Int = ship.capacity + if (ShipCompartment.CREW_QUARTERS in ship.compartments) 2 else 0

    fun recruit(crew: CrewState, ship: ShipState, member: CrewMemberState): CrewState {
        require(member.npcId !in crew.members) { "Crew member ${member.npcId} already recruited" }
        require(member.status !in setOf(CrewStatus.DEAD, CrewStatus.DESERTED)) { "Unavailable NPC cannot be recruited" }
        require(rosterCount(crew) < capacityFor(ship)) { "Ship crew capacity reached" }
        return crew.copy(members = crew.members + (member.npcId to member))
    }

    fun assignRole(member: CrewMemberState, role: CrewRole): CrewMemberState {
        require(member.status !in setOf(CrewStatus.DEAD, CrewStatus.DESERTED)) { "Unavailable crew cannot be assigned" }
        return member.copy(role = role)
    }

    fun changeLoyalty(member: CrewMemberState, delta: Int): CrewMemberState =
        member.copy(loyalty = (member.loyalty + delta).coerceIn(-100, 100))

    fun changeAffinity(member: CrewMemberState, playerId: String, delta: Int): CrewMemberState {
        require(playerId == "p1" || playerId == "p2") { "Crew affinity player must be p1 or p2" }
        val current = member.playerAffinity[playerId] ?: 0
        return member.copy(playerAffinity = member.playerAffinity + (playerId to (current + delta).coerceIn(-100, 100)))
    }

    fun injure(member: CrewMemberState, severity: Int): CrewMemberState {
        require(severity in 1..3) { "Crew injury severity must be in 1..3" }
        require(member.status !in setOf(CrewStatus.DEAD, CrewStatus.DESERTED)) { "Unavailable crew cannot be injured" }
        val injury = maxOf(member.injurySeverity, severity)
        val status = if (member.status in setOf(CrewStatus.ACTIVE, CrewStatus.WOUNDED)) CrewStatus.WOUNDED else member.status
        return member.copy(injurySeverity = injury, status = status)
    }

    fun heal(member: CrewMemberState, amount: Int): CrewMemberState {
        require(amount > 0) { "Healing amount must be positive" }
        require(member.status !in setOf(CrewStatus.DEAD, CrewStatus.DESERTED)) { "Unavailable crew cannot be healed" }
        val remaining = (member.injurySeverity - amount).coerceAtLeast(0)
        val status = when {
            member.status in setOf(CrewStatus.CAPTURED, CrewStatus.MISSING) -> member.status
            remaining > 0 -> CrewStatus.WOUNDED
            else -> CrewStatus.ACTIVE
        }
        return member.copy(injurySeverity = remaining, status = status)
    }

    fun capture(member: CrewMemberState): CrewMemberState {
        require(member.status !in setOf(CrewStatus.DEAD, CrewStatus.DESERTED)) { "Unavailable crew cannot be captured" }
        return member.copy(status = CrewStatus.CAPTURED)
    }

    fun markMissing(member: CrewMemberState): CrewMemberState {
        require(member.status !in setOf(CrewStatus.DEAD, CrewStatus.DESERTED)) { "Unavailable crew cannot go missing" }
        return member.copy(status = CrewStatus.MISSING)
    }

    fun returnActive(member: CrewMemberState): CrewMemberState {
        require(member.status in setOf(CrewStatus.CAPTURED, CrewStatus.MISSING, CrewStatus.WOUNDED)) { "Crew member is not recoverable to active duty" }
        return member.copy(status = if (member.injurySeverity > 0) CrewStatus.WOUNDED else CrewStatus.ACTIVE)
    }

    fun kill(member: CrewMemberState): CrewMemberState = member.copy(status = CrewStatus.DEAD)

    fun resolveDesertion(member: CrewMemberState, severeCrisis: Boolean): CrewMemberState {
        if (!severeCrisis) return member
        if (member.status !in setOf(CrewStatus.ACTIVE, CrewStatus.WOUNDED)) return member
        return if (member.loyalty <= -50) member.copy(status = CrewStatus.DESERTED) else member
    }

    fun effectiveCompetence(member: CrewMemberState): Int = when (member.status) {
        CrewStatus.ACTIVE -> member.competence
        CrewStatus.WOUNDED -> (member.competence - member.injurySeverity).coerceAtLeast(0)
        CrewStatus.CAPTURED,
        CrewStatus.MISSING,
        CrewStatus.DESERTED,
        CrewStatus.DEAD -> 0
    }

    fun bestCompetence(crew: CrewState, role: CrewRole): Int = crew.members.values
        .asSequence()
        .filter { it.role == role }
        .maxOfOrNull(::effectiveCompetence)
        ?: 0

    fun supplyUpkeep(crew: CrewState): Int = crew.members.values.count {
        it.status in setOf(CrewStatus.ACTIVE, CrewStatus.WOUNDED)
    }

    fun rosterCount(crew: CrewState): Int = crew.members.values.count { it.status !in setOf(CrewStatus.DEAD, CrewStatus.DESERTED) }
}
