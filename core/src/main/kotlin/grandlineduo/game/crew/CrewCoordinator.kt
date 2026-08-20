package grandlineduo.game.crew

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore

/** Host-only authority for persistent crew mutations. */
class CrewCoordinator(
    private val hostReplica: HostReplica,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun recruit(commandId: String, member: CrewMemberState, hostTimestamp: Long): CampaignEvent = changeWorld(
        commandId = commandId,
        fingerprint = "crew-recruit|${fingerprint(member)}",
        hostTimestamp = hostTimestamp,
        metadata = mapOf("meta.crew" to "RECRUITED", "meta.npcId" to member.npcId),
    ) { before ->
        val ship = before.shipState ?: throw IllegalArgumentException("Campaign has no ship")
        before.copy(crewState = CrewEngine.recruit(before.crewState, ship, member))
    }

    @Synchronized
    fun assignRole(commandId: String, npcId: String, role: CrewRole, hostTimestamp: Long): CampaignEvent =
        updateMember(commandId, npcId, "crew-role|$npcId|${role.name}", hostTimestamp, "ROLE_ASSIGNED") {
            CrewEngine.assignRole(it, role)
        }

    @Synchronized
    fun changeLoyalty(commandId: String, npcId: String, delta: Int, hostTimestamp: Long): CampaignEvent =
        updateMember(commandId, npcId, "crew-loyalty|$npcId|$delta", hostTimestamp, "LOYALTY_CHANGED") {
            CrewEngine.changeLoyalty(it, delta)
        }

    @Synchronized
    fun changeAffinity(commandId: String, npcId: String, playerId: String, delta: Int, hostTimestamp: Long): CampaignEvent =
        updateMember(commandId, npcId, "crew-affinity|$npcId|$playerId|$delta", hostTimestamp, "AFFINITY_CHANGED") {
            CrewEngine.changeAffinity(it, playerId, delta)
        }

    @Synchronized
    fun injure(commandId: String, npcId: String, severity: Int, hostTimestamp: Long): CampaignEvent =
        updateMember(commandId, npcId, "crew-injury|$npcId|$severity", hostTimestamp, "INJURED") {
            CrewEngine.injure(it, severity)
        }

    @Synchronized
    fun heal(commandId: String, npcId: String, amount: Int, hostTimestamp: Long): CampaignEvent =
        updateMember(commandId, npcId, "crew-heal|$npcId|$amount", hostTimestamp, "HEALED") {
            CrewEngine.heal(it, amount)
        }

    @Synchronized
    fun capture(commandId: String, npcId: String, hostTimestamp: Long): CampaignEvent =
        updateMember(commandId, npcId, "crew-capture|$npcId", hostTimestamp, "CAPTURED", CrewEngine::capture)

    @Synchronized
    fun rescue(commandId: String, npcId: String, hostTimestamp: Long): CampaignEvent =
        updateMember(commandId, npcId, "crew-rescue|$npcId", hostTimestamp, "RESCUED", CrewEngine::returnActive)

    @Synchronized
    fun markMissing(commandId: String, npcId: String, hostTimestamp: Long): CampaignEvent =
        updateMember(commandId, npcId, "crew-missing|$npcId", hostTimestamp, "MISSING", CrewEngine::markMissing)

    @Synchronized
    fun returnActive(commandId: String, npcId: String, hostTimestamp: Long): CampaignEvent =
        updateMember(commandId, npcId, "crew-return|$npcId", hostTimestamp, "RETURNED", CrewEngine::returnActive)

    @Synchronized
    fun kill(commandId: String, npcId: String, hostTimestamp: Long): CampaignEvent =
        updateMember(commandId, npcId, "crew-death|$npcId", hostTimestamp, "DIED", CrewEngine::kill)

    @Synchronized
    fun resolveDesertion(
        commandId: String,
        npcId: String,
        severeCrisis: Boolean,
        hostTimestamp: Long,
    ): CampaignEvent = updateMember(
        commandId,
        npcId,
        "crew-desertion|$npcId|$severeCrisis",
        hostTimestamp,
        "DESERTION_CHECKED",
    ) { CrewEngine.resolveDesertion(it, severeCrisis) }

    private fun updateMember(
        commandId: String,
        npcId: String,
        fingerprint: String,
        hostTimestamp: Long,
        metadataType: String,
        transform: (CrewMemberState) -> CrewMemberState,
    ): CampaignEvent = changeWorld(
        commandId = commandId,
        fingerprint = fingerprint,
        hostTimestamp = hostTimestamp,
        metadata = mapOf("meta.crew" to metadataType, "meta.npcId" to npcId),
    ) { before ->
        val current = before.crewState.members[npcId] ?: throw IllegalArgumentException("Unknown crew member $npcId")
        val next = transform(current)
        before.copy(crewState = before.crewState.copy(members = before.crewState.members + (npcId to next)))
    }

    private fun changeWorld(
        commandId: String,
        fingerprint: String,
        hostTimestamp: Long,
        metadata: Map<String, String>,
        transform: (WorldState) -> WorldState,
    ): CampaignEvent {
        hostReplica.events.firstOrNull { it.commandId == commandId }?.let { existing ->
            require(existing.commandFingerprint == fingerprint) { "Command ID collision" }
            persist(existing)
            return existing
        }
        val next = transform(hostReplica.state)
        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = commandId,
                actorId = "gm",
                nextState = next,
                sourceFingerprint = fingerprint,
                metadata = metadata,
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun persist(event: CampaignEvent) {
        if (durableStore != null) durableStore.commit(event, hostReplica.state)
        else snapshotStore?.save(hostReplica.state)
    }

    private fun fingerprint(member: CrewMemberState): String = buildString {
        append(member.npcId).append('|').append(member.name).append('|').append(member.role.name).append('|')
        append(member.competence).append('|').append(member.loyalty).append('|').append(member.injurySeverity).append('|')
        append(member.status.name).append('|')
        member.playerAffinity.toSortedMap().forEach { (player, affinity) -> append(player).append('=').append(affinity).append(';') }
    }
}
