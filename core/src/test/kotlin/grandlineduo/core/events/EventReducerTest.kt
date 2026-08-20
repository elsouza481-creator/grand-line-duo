package grandlineduo.core.events

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object EventReducerTest {
    fun register() {
        test("event reducer applies a valid next event and verifies hashes") {
            val before = WorldState(campaignId = "c1", partyBerries = 100)
            val preview = EventReducer.preview(
                before,
                eventType = EventType.BERRIES_CHANGED,
                payload = mapOf("delta" to "50"),
            )
            val event = CampaignEvent(
                eventId = 1,
                campaignId = "c1",
                eventType = EventType.BERRIES_CHANGED,
                actorId = "p1",
                payloadVersion = 1,
                payload = mapOf("delta" to "50"),
                hostTimestamp = 1234,
                stateHashBefore = CanonicalStateHasher.hash(before),
                stateHashAfter = CanonicalStateHasher.hash(preview),
                commandId = "cmd-1",
            )

            val after = EventReducer.apply(before, event)

            assertEquals(150L, after.partyBerries)
            assertEquals(1L, after.lastEventId)
            assertEquals(event.stateHashAfter, CanonicalStateHasher.hash(after))
        }

        test("event reducer rejects a wrong before hash") {
            val before = WorldState(campaignId = "c1", partyBerries = 100)
            val event = CampaignEvent(
                eventId = 1,
                campaignId = "c1",
                eventType = EventType.BERRIES_CHANGED,
                actorId = "p1",
                payloadVersion = 1,
                payload = mapOf("delta" to "50"),
                hostTimestamp = 1234,
                stateHashBefore = "bad",
                stateHashAfter = "bad",
                commandId = "cmd-1",
            )

            var failed = false
            try { EventReducer.apply(before, event) } catch (_: IllegalArgumentException) { failed = true }
            assertEquals(true, failed)
        }
    }
}
