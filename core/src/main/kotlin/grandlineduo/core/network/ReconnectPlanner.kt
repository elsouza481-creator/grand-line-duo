package grandlineduo.core.network

import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.WorldState

sealed interface SyncPlan {
    data object UpToDate : SyncPlan
    data class Delta(val events: List<CampaignEvent>) : SyncPlan
    data class FullSnapshot(val state: WorldState) : SyncPlan
}

class ReconnectPlanner(
    private val initialState: WorldState,
    private val currentState: WorldState,
    events: List<CampaignEvent>,
) {
    private val orderedEvents = events.sortedBy { it.eventId }

    init {
        require(initialState.campaignId == currentState.campaignId) { "Campaign mismatch in planner state" }
        require(orderedEvents.zipWithNext().all { (a, b) -> b.eventId == a.eventId + 1 }) {
            "Reconnect planner requires contiguous event history"
        }
    }

    fun plan(hello: ReconnectHello): SyncPlan {
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            throw ProtocolNegotiationException("Protocol mismatch")
        }
        if (hello.campaignId != currentState.campaignId) {
            throw ProtocolNegotiationException("Campaign mismatch")
        }
        if (hello.lastConfirmedEventId < 0 || hello.lastConfirmedEventId > currentState.lastEventId) {
            return SyncPlan.FullSnapshot(currentState)
        }

        val expectedHash = hashAt(hello.lastConfirmedEventId)
            ?: return SyncPlan.FullSnapshot(currentState)
        if (expectedHash != hello.stateHash) {
            return SyncPlan.FullSnapshot(currentState)
        }
        if (hello.lastConfirmedEventId == currentState.lastEventId) {
            return SyncPlan.UpToDate
        }

        val delta = orderedEvents.filter { it.eventId > hello.lastConfirmedEventId }
        if (delta.isEmpty() || delta.first().eventId != hello.lastConfirmedEventId + 1) {
            return SyncPlan.FullSnapshot(currentState)
        }
        return SyncPlan.Delta(delta)
    }

    private fun hashAt(eventId: Long): String? {
        if (eventId == initialState.lastEventId) return CanonicalStateHasher.hash(initialState)
        return orderedEvents.firstOrNull { it.eventId == eventId }?.stateHashAfter
    }
}
