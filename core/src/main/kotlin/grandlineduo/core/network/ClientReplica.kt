package grandlineduo.core.network

import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.events.EventReducer
import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.WorldState
import java.util.TreeMap

class ClientReplica(initialState: WorldState) {
    private val pending = TreeMap<Long, CampaignEvent>()

    var state: WorldState = initialState
        private set

    fun receive(event: CampaignEvent) {
        require(event.campaignId == state.campaignId) { "Campaign mismatch" }
        if (event.eventId <= state.lastEventId) return
        pending.putIfAbsent(event.eventId, event)
        drainContiguousEvents()
    }

    fun reconnectHello(peerId: String = "p2"): ReconnectHello = ReconnectHello(
        protocolVersion = PROTOCOL_VERSION,
        campaignId = state.campaignId,
        lastConfirmedEventId = state.lastEventId,
        stateHash = CanonicalStateHasher.hash(state),
        peerId = peerId,
    )

    fun applySyncPlan(plan: SyncPlan) {
        when (plan) {
            SyncPlan.UpToDate -> Unit
            is SyncPlan.Delta -> plan.events.forEach(::receive)
            is SyncPlan.FullSnapshot -> {
                require(plan.state.campaignId == state.campaignId) { "Campaign mismatch" }
                state = plan.state
                pending.clear()
            }
        }
    }

    private fun drainContiguousEvents() {
        while (true) {
            val nextId = state.lastEventId + 1
            val next = pending.remove(nextId) ?: return
            state = EventReducer.apply(state, next)
        }
    }
}
