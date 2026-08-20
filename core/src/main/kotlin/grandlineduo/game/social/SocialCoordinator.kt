package grandlineduo.game.social

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore

object SocialWorldFlags {
    const val PREFIX = "SOCIAL_"
    const val HAS_ALLY = "SOCIAL_HAS_ALLY"
    const val HAS_RIVAL = "SOCIAL_HAS_RIVAL"
    const val HAS_ENEMY = "SOCIAL_HAS_ENEMY"
    const val HAS_ALLIED_FACTION = "SOCIAL_HAS_ALLIED_FACTION"
    const val HAS_HOSTILE_FACTION = "SOCIAL_HAS_HOSTILE_FACTION"

    fun npcAlly(npcId: String) = "SOCIAL_NPC_ALLY:$npcId"
    fun npcRival(npcId: String) = "SOCIAL_NPC_RIVAL:$npcId"
    fun npcEnemy(npcId: String) = "SOCIAL_NPC_ENEMY:$npcId"
    fun factionAllied(factionId: String) = "SOCIAL_FACTION_ALLIED:$factionId"
    fun factionHostile(factionId: String) = "SOCIAL_FACTION_HOSTILE:$factionId"
}

/** Host-only boundary for persistent faction and NPC memory. */
class SocialCoordinator(
    private val hostReplica: HostReplica,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun applyIncident(
        commandId: String,
        incident: SocialIncident,
        hostTimestamp: Long,
    ): CampaignEvent {
        val fingerprint = buildString {
            append("social-incident|")
            append(incident.type.name).append('|')
            append(incident.npcId ?: "").append('|')
            append(incident.factionId ?: "")
        }
        hostReplica.events.firstOrNull { it.commandId == commandId }?.let { existing ->
            require(existing.commandFingerprint == fingerprint) { "Command ID collision" }
            persist(existing)
            return existing
        }

        val before = hostReplica.state
        val nextSocial = SocialConsequenceEngine.apply(before.socialState, incident)
        val nextFlags = socialFlags(before.worldFlags, nextSocial)
        val nextWorld = before.copy(socialState = nextSocial, worldFlags = nextFlags)
        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = commandId,
                actorId = "gm",
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = mapOf(
                    "meta.social" to "INCIDENT_APPLIED",
                    "meta.socialType" to incident.type.name,
                    "meta.npcId" to (incident.npcId ?: ""),
                    "meta.factionId" to (incident.factionId ?: ""),
                ),
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun socialFlags(existing: Map<String, String>, social: SocialState): Map<String, String> {
        val flags = existing.filterKeys { !it.startsWith(SocialWorldFlags.PREFIX) }.toMutableMap()
        val active = social.npcRelationships.filterValues { it.status == NpcStatus.ACTIVE }
        if (active.values.any { it.bond == NpcBond.ALLY }) flags[SocialWorldFlags.HAS_ALLY] = "true"
        if (active.values.any { it.bond == NpcBond.RIVAL }) flags[SocialWorldFlags.HAS_RIVAL] = "true"
        if (active.values.any { it.bond == NpcBond.ENEMY }) flags[SocialWorldFlags.HAS_ENEMY] = "true"
        active.forEach { (npcId, relation) ->
            when (relation.bond) {
                NpcBond.ALLY -> flags[SocialWorldFlags.npcAlly(npcId)] = "true"
                NpcBond.RIVAL -> flags[SocialWorldFlags.npcRival(npcId)] = "true"
                NpcBond.ENEMY -> flags[SocialWorldFlags.npcEnemy(npcId)] = "true"
                NpcBond.NEUTRAL, NpcBond.ACQUAINTANCE -> Unit
            }
        }
        if (social.factionStanding.values.any { it >= 60 }) flags[SocialWorldFlags.HAS_ALLIED_FACTION] = "true"
        if (social.factionStanding.values.any { it <= -60 }) flags[SocialWorldFlags.HAS_HOSTILE_FACTION] = "true"
        social.factionStanding.forEach { (factionId, standing) ->
            when {
                standing >= 60 -> flags[SocialWorldFlags.factionAllied(factionId)] = "true"
                standing <= -60 -> flags[SocialWorldFlags.factionHostile(factionId)] = "true"
            }
        }
        return flags
    }

    private fun persist(event: CampaignEvent) {
        if (durableStore != null) durableStore.commit(event, hostReplica.state)
        else snapshotStore?.save(hostReplica.state)
    }
}
