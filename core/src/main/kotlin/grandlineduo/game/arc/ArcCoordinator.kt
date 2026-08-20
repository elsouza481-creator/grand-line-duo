package grandlineduo.game.arc

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore

/** Host-only authority for persistent cooperative narrative arcs. */
class ArcCoordinator(
    private val hostReplica: HostReplica,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun startArc(commandId: String, context: ArcStartContext, hostTimestamp: Long): CampaignEvent {
        val fingerprint = startFingerprint(context)
        hostReplica.events.firstOrNull { it.commandId == commandId }?.let { existing ->
            require(existing.commandFingerprint == fingerprint) { "Command ID collision" }
            persist(existing)
            return existing
        }
        val current = hostReplica.state.activeArc
        require(current == null || current.phase == ArcPhase.COMPLETE) { "An arc is already active" }
        require(context.islandId == hostReplica.state.islandId) { "Arc context island is stale" }
        val arc = ArcEngine.start(context)
        val nextWorld = hostReplica.state.copy(activeArc = arc)
        val event = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = commandId,
                actorId = "gm",
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = mapOf(
                    "meta.arc" to "STARTED",
                    "meta.arcId" to arc.arcId,
                    "meta.arcArchetype" to arc.archetype.name,
                    "meta.arcPhase" to arc.phase.name,
                ),
            ),
            hostTimestamp,
        ).event
        persist(event)
        return event
    }

    @Synchronized
    fun choose(commandId: String, playerId: String, choiceId: String, hostTimestamp: Long): CampaignEvent {
        require(playerId == "p1" || playerId == "p2") { "Unknown player $playerId" }
        val fingerprint = "arc-choice|$playerId|$choiceId"
        hostReplica.events.firstOrNull { it.commandId == commandId }?.let { existing ->
            require(existing.commandFingerprint == fingerprint) { "Command ID collision" }
            persist(existing)
            return existing
        }
        require(hostReplica.state.activeCombat == null) { "Arc choice is blocked while combat is active" }
        val current = hostReplica.state.activeArc ?: throw IllegalArgumentException("No active arc")
        val outcome = ArcEngine.choose(current, playerId, choiceId)
        val nextFlags = if (outcome.state.phase == ArcPhase.COMPLETE) {
            val prefix = "ARC_HISTORY:${outcome.state.arcId}:"
            hostReplica.state.worldFlags.toMutableMap().also { flags ->
                flags[prefix + "COMPLETE"] = "true"
                flags[prefix + "ARCHETYPE"] = outcome.state.archetype.name
                flags[prefix + "ESCALATION"] = outcome.state.escalation.toString()
                outcome.state.sharedFlags.sorted().forEach { flag ->
                    flags[prefix + "FLAG:" + flag] = "true"
                }
            }
        } else hostReplica.state.worldFlags
        val bossCombat = if (current.phase == ArcPhase.CLIMAX && outcome.state.phase == ArcPhase.AFTERMATH) {
            ArcBossFactory.create(hostReplica.state, outcome.state)
        } else null
        val nextWorld = hostReplica.state.copy(
            activeArc = outcome.state,
            activeCombat = bossCombat,
            worldFlags = nextFlags,
        )
        val metadata = mutableMapOf(
            "meta.arc" to "CHOICE_APPLIED",
            "meta.arcId" to outcome.state.arcId,
            "meta.arcPhase" to outcome.state.phase.name,
            "meta.arcPlayer" to playerId,
            "meta.arcChoice" to choiceId,
            "meta.arcEscalation" to outcome.state.escalation.toString(),
            "meta.arcBossStarted" to (bossCombat != null).toString(),
        )
        outcome.beats.forEachIndexed { index, beat ->
            metadata["meta.arcBeat.$index.visible"] = beat.visibleTo.sorted().joinToString(",")
            metadata["meta.arcBeat.$index.text"] = beat.text
        }
        val event = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = commandId,
                actorId = playerId,
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = metadata,
            ),
            hostTimestamp,
        ).event
        persist(event)
        return event
    }

    private fun persist(event: CampaignEvent) {
        if (durableStore != null) durableStore.commit(event, hostReplica.state)
        else snapshotStore?.save(hostReplica.state)
    }

    private fun startFingerprint(context: ArcStartContext): String = buildString {
        append("arc-start|").append(context.seed).append('|').append(context.islandId).append('|')
        append(context.totalBounty).append('|')
        context.presentFactions.sorted().forEach { append("f=").append(it).append(';') }
        context.worldFlags.sorted().forEach { append("w=").append(it).append(';') }
    }
}
