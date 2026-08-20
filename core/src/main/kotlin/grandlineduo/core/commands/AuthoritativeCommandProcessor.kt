package grandlineduo.core.commands

import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.events.EventReducer
import grandlineduo.core.events.EventType
import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import java.util.Base64

data class CommandResult(
    val event: CampaignEvent,
    val wasDuplicate: Boolean,
)

class AuthoritativeCommandProcessor(
    initialState: WorldState,
    recoveredEvents: List<CampaignEvent> = emptyList(),
) {
    private data class CachedCommand(val fingerprint: String, val event: CampaignEvent)

    private val processed = recoveredEvents.associate { event ->
        event.commandId to CachedCommand(event.commandFingerprint, event)
    }.toMutableMap()
    var state: WorldState = initialState
        private set

    fun submit(command: Command, hostTimestamp: Long): CommandResult {
        val fingerprint = command.fingerprint()
        processed[command.commandId]?.let { cached ->
            require(cached.fingerprint == fingerprint) { "Command ID collision" }
            return CommandResult(cached.event, wasDuplicate = true)
        }

        val (eventType, payload) = when (command) {
            is GrantBerriesCommand -> EventType.BERRIES_CHANGED to mapOf("delta" to command.amount.toString())
            is ReplaceWorldStateCommand -> {
                require(command.nextState.campaignId == state.campaignId) { "Campaign mismatch" }
                require(command.nextState.lastEventId == state.lastEventId) { "Replacement state is stale" }
                val encoded = Base64.getEncoder().encodeToString(WorldStateCodec.encode(command.nextState))
                EventType.WORLD_REPLACED to (mapOf("state" to encoded) + command.metadata)
            }
        }

        val beforeHash = CanonicalStateHasher.hash(state)
        val preview = EventReducer.preview(state, eventType, payload)
        val event = CampaignEvent(
            eventId = state.lastEventId + 1,
            campaignId = state.campaignId,
            eventType = eventType,
            actorId = command.actorId,
            payloadVersion = 1,
            payload = payload,
            hostTimestamp = hostTimestamp,
            stateHashBefore = beforeHash,
            stateHashAfter = CanonicalStateHasher.hash(preview),
            commandId = command.commandId,
            commandFingerprint = fingerprint,
        )
        state = EventReducer.apply(state, event)
        processed[command.commandId] = CachedCommand(fingerprint, event)
        return CommandResult(event, wasDuplicate = false)
    }
}
