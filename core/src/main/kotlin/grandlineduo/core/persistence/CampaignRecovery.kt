package grandlineduo.core.persistence

import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.events.EventReducer
import grandlineduo.core.model.WorldState

data class RecoveredCampaign(
    val state: WorldState,
    val events: List<CampaignEvent>,
)

class CampaignRecovery(private val directory: java.nio.file.Path) {
    fun recover(): RecoveredCampaign {
        var state = SnapshotStore(directory).loadLatestValid()
            ?: throw IllegalStateException("No valid campaign snapshot")
        val events = EventLog(directory).readValidPrefix(truncateIncompleteTail = true)
        require(events.all { it.campaignId == state.campaignId }) { "Event log campaign mismatch" }

        val tail = events.filter { it.eventId > state.lastEventId }.sortedBy { it.eventId }
        for (event in tail) {
            state = EventReducer.apply(state, event)
        }
        return RecoveredCampaign(state, events)
    }
}
