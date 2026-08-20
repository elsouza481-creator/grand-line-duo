package grandlineduo.core.events

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import java.util.Base64

object EventReducer {
    fun preview(
        state: WorldState,
        eventType: EventType,
        payload: Map<String, String>,
    ): WorldState {
        val nextId = state.lastEventId + 1
        return applyPayload(state, eventType, payload).copy(lastEventId = nextId)
    }

    fun apply(state: WorldState, event: CampaignEvent): WorldState {
        require(event.campaignId == state.campaignId) { "Campaign mismatch" }
        require(event.eventId == state.lastEventId + 1) {
            "Expected event ${state.lastEventId + 1}, got ${event.eventId}"
        }
        require(event.stateHashBefore == CanonicalStateHasher.hash(state)) { "Before hash mismatch" }

        val next = applyPayload(state, event.eventType, event.payload).copy(lastEventId = event.eventId)
        require(event.stateHashAfter == CanonicalStateHasher.hash(next)) { "After hash mismatch" }
        return next
    }

    private fun applyPayload(
        state: WorldState,
        eventType: EventType,
        payload: Map<String, String>,
    ): WorldState = when (eventType) {
        EventType.BERRIES_CHANGED -> {
            val delta = payload["delta"]?.toLongOrNull()
                ?: throw IllegalArgumentException("Missing or invalid berries delta")
            state.copy(partyBerries = state.partyBerries + delta)
        }
        EventType.FLAG_SET -> {
            val key = payload["key"] ?: throw IllegalArgumentException("Missing flag key")
            val value = payload["value"] ?: throw IllegalArgumentException("Missing flag value")
            state.copy(worldFlags = state.worldFlags + (key to value))
        }
        EventType.ISLAND_CHANGED -> {
            val islandId = payload["islandId"] ?: throw IllegalArgumentException("Missing islandId")
            state.copy(islandId = islandId)
        }
        EventType.WORLD_REPLACED -> {
            val encoded = payload["state"] ?: throw IllegalArgumentException("Missing replacement state")
            val replacement = try {
                WorldStateCodec.decode(Base64.getDecoder().decode(encoded))
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid replacement state: ${e.message}")
            }
            require(replacement.campaignId == state.campaignId) { "Replacement campaign mismatch" }
            require(replacement.lastEventId == state.lastEventId) { "Replacement state is based on a stale event" }
            replacement
        }
    }
}
