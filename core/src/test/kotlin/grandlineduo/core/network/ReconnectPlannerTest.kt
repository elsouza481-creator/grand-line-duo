package grandlineduo.core.network

import grandlineduo.core.commands.AuthoritativeCommandProcessor
import grandlineduo.core.commands.GrantBerriesCommand
import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ReconnectPlannerTest {
    fun register() {
        test("matching current event and hash is already up to date") {
            val fixture = fixture(2)
            val plan = fixture.planner.plan(
                ReconnectHello(PROTOCOL_VERSION, "c1", 2, CanonicalStateHasher.hash(fixture.current))
            )
            assertTrue(plan is SyncPlan.UpToDate)
        }

        test("known older hash receives only missing event delta") {
            val fixture = fixture(3)
            val event1Hash = fixture.events.first().stateHashAfter
            val plan = fixture.planner.plan(ReconnectHello(PROTOCOL_VERSION, "c1", 1, event1Hash))
            assertTrue(plan is SyncPlan.Delta)
            val delta = plan as SyncPlan.Delta
            assertEquals(listOf(2L, 3L), delta.events.map { it.eventId })
        }

        test("divergent hash forces full snapshot") {
            val fixture = fixture(2)
            val plan = fixture.planner.plan(ReconnectHello(PROTOCOL_VERSION, "c1", 1, "not-the-real-hash"))
            assertTrue(plan is SyncPlan.FullSnapshot)
            assertEquals(fixture.current, (plan as SyncPlan.FullSnapshot).state)
        }

        test("wrong campaign is rejected") {
            val fixture = fixture(1)
            var failed = false
            try { fixture.planner.plan(ReconnectHello(PROTOCOL_VERSION, "other", 0, fixture.initialHash)) }
            catch (_: ProtocolNegotiationException) { failed = true }
            assertEquals(true, failed)
        }

        test("protocol mismatch is rejected") {
            val fixture = fixture(1)
            var failed = false
            try { fixture.planner.plan(ReconnectHello(PROTOCOL_VERSION + 1, "c1", 0, fixture.initialHash)) }
            catch (_: ProtocolNegotiationException) { failed = true }
            assertEquals(true, failed)
        }

        test("protocol envelope checksum detects payload mutation") {
            val envelope = ProtocolEnvelope.create(
                campaignId = "c1",
                sessionId = "session-1",
                senderId = "p2",
                sequenceNumber = 8,
                ackNumber = 6,
                messageType = "COMMAND",
                payload = mapOf("commandId" to "cmd-8", "amount" to "25"),
            )
            assertEquals(true, envelope.hasValidChecksum())
            assertEquals(false, envelope.copy(payload = envelope.payload + ("amount" to "26")).hasValidChecksum())
        }
    }

    private data class Fixture(
        val planner: ReconnectPlanner,
        val current: WorldState,
        val events: List<grandlineduo.core.events.CampaignEvent>,
        val initialHash: String,
    )

    private fun fixture(eventCount: Int): Fixture {
        val initial = WorldState(campaignId = "c1", partyBerries = 0)
        val processor = AuthoritativeCommandProcessor(initial)
        val events = (1..eventCount).map { index ->
            processor.submit(GrantBerriesCommand("cmd-$index", "p1", 10), 1000L + index).event
        }
        return Fixture(
            planner = ReconnectPlanner(initial, processor.state, events),
            current = processor.state,
            events = events,
            initialHash = CanonicalStateHasher.hash(initial),
        )
    }
}
