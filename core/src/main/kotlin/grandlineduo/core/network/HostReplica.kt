package grandlineduo.core.network

import grandlineduo.core.commands.AuthoritativeCommandProcessor
import grandlineduo.core.commands.Command
import grandlineduo.core.commands.CommandResult
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.model.WorldState

class HostReplica(
    private val initialState: WorldState,
    recoveredState: WorldState = initialState,
    recoveredEvents: List<CampaignEvent> = emptyList(),
) {
    private val processor = AuthoritativeCommandProcessor(recoveredState, recoveredEvents)
    private val eventHistory = recoveredEvents.sortedBy { it.eventId }.toMutableList()

    val state: WorldState get() = processor.state
    val events: List<CampaignEvent> get() = eventHistory.toList()

    init {
        require(initialState.campaignId == recoveredState.campaignId) { "Campaign mismatch" }
        require(recoveredState.lastEventId == (eventHistory.lastOrNull()?.eventId ?: initialState.lastEventId)) {
            "Recovered state/event history mismatch"
        }
    }

    fun submit(command: Command, hostTimestamp: Long): CommandResult {
        val result = processor.submit(command, hostTimestamp)
        if (!result.wasDuplicate) eventHistory += result.event
        return result
    }

    fun planReconnect(hello: ReconnectHello): SyncPlan =
        ReconnectPlanner(initialState, state, eventHistory).plan(hello)
}
