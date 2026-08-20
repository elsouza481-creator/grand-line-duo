package grandlineduo.game.notoriety

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.powers.DevilFruitCategory

object NotorietyWorldFlags {
    const val GOVERNMENT_KNOWS_HAOSHOKU = "GOVERNMENT_KNOWS_HAOSHOKU"
    const val GOVERNMENT_KNOWS_LOGIA = "GOVERNMENT_KNOWS_LOGIA"
    const val GOVERNMENT_KNOWS_ZOAN = "GOVERNMENT_KNOWS_ZOAN"
    const val GOVERNMENT_KNOWS_PARAMECIA = "GOVERNMENT_KNOWS_PARAMECIA"
    const val MARINE_RESPONSE_PREFIX = "MARINE_RESPONSE_"
}

/** Host-only boundary for public bounty, hidden Government threat, and local Marine response. */
class NotorietyCoordinator(
    private val hostReplica: HostReplica,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun applyIncident(
        commandId: String,
        playerId: String,
        incident: BountyIncident,
        marinesCanReach: Boolean,
        hostTimestamp: Long,
    ): CampaignEvent {
        val fingerprint = buildString {
            append("notoriety-incident|")
            append(playerId).append('|')
            append(incident.type.name).append('|')
            append(incident.severity).append('|')
            append(incident.visibility.name).append('|')
            append(marinesCanReach)
        }
        hostReplica.events.firstOrNull { it.commandId == commandId }?.let { existing ->
            require(existing.commandFingerprint == fingerprint) { "Command ID collision" }
            persist(existing)
            return existing
        }

        val before = hostReplica.state
        val player = before.players[playerId] ?: throw IllegalArgumentException("Unknown player $playerId")
        val assessment = BountyEngine.assess(player.bounty, incident)
        val nextThreat = minOf(
            MAX_INTERNAL_THREAT_POINTS.toLong(),
            before.governmentThreatPoints.toLong() + assessment.internalThreatPoints.toLong(),
        ).toInt()
        val players = before.players + (playerId to player.copy(bounty = assessment.newBounty))
        val provisional = before.copy(
            players = players,
            governmentThreatPoints = nextThreat,
        )
        val plan = MarineResponsePlanner.plan(
            MarineResponseContext(
                totalBounty = totalBounty(provisional),
                internalThreatPoints = nextThreat,
                marinesCanReach = marinesCanReach,
                exposedHaoshoku = provisional.worldFlags[NotorietyWorldFlags.GOVERNMENT_KNOWS_HAOSHOKU] == "true",
                exposedDevilFruitCategory = knownFruitCategory(provisional),
            )
        )
        val responseFlags = provisional.worldFlags
            .filterKeys { !it.startsWith(NotorietyWorldFlags.MARINE_RESPONSE_PREFIX) }
            .toMutableMap()
            .also { flags -> plan.directorFlags.forEach { flags[it] = "true" } }
        val nextWorld = provisional.copy(worldFlags = responseFlags)

        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = commandId,
                actorId = "gm",
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = mapOf(
                    "meta.notoriety" to "INCIDENT_ASSESSED",
                    "meta.playerId" to playerId,
                    "meta.incidentType" to incident.type.name,
                    "meta.visibility" to incident.visibility.name,
                    "meta.bountyDelta" to assessment.delta.toString(),
                    "meta.governmentThreatDelta" to assessment.internalThreatPoints.toString(),
                    "meta.marineResponseTier" to plan.tier.name,
                ),
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun totalBounty(state: WorldState): Long =
        state.players.values.fold(0L) { total, player -> Math.addExact(total, player.bounty) }

    private fun knownFruitCategory(state: WorldState): DevilFruitCategory? = when {
        state.worldFlags[NotorietyWorldFlags.GOVERNMENT_KNOWS_LOGIA] == "true" -> DevilFruitCategory.LOGIA
        state.worldFlags[NotorietyWorldFlags.GOVERNMENT_KNOWS_ZOAN] == "true" -> DevilFruitCategory.ZOAN
        state.worldFlags[NotorietyWorldFlags.GOVERNMENT_KNOWS_PARAMECIA] == "true" -> DevilFruitCategory.PARAMECIA
        else -> null
    }

    private fun persist(event: CampaignEvent) {
        if (durableStore != null) durableStore.commit(event, hostReplica.state)
        else snapshotStore?.save(hostReplica.state)
    }

    companion object {
        const val MAX_INTERNAL_THREAT_POINTS = 1_000_000
    }
}
