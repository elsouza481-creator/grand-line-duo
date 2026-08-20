package grandlineduo.core.events

enum class EventType {
    BERRIES_CHANGED,
    FLAG_SET,
    ISLAND_CHANGED,
    WORLD_REPLACED,
}

data class CampaignEvent(
    val eventId: Long,
    val campaignId: String,
    val eventType: EventType,
    val actorId: String,
    val payloadVersion: Int,
    val payload: Map<String, String>,
    val hostTimestamp: Long,
    val stateHashBefore: String,
    val stateHashAfter: String,
    val commandId: String,
    val commandFingerprint: String = "",
)
