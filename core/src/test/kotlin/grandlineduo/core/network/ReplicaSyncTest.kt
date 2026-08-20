package grandlineduo.core.network

import grandlineduo.core.commands.GrantBerriesCommand
import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object ReplicaSyncTest {
    fun register() {
        test("duplicate event delivery does not apply state twice") {
            val initial = WorldState(campaignId = "c1")
            val host = HostReplica(initial)
            val client = ClientReplica(initial)
            val event = host.submit(GrantBerriesCommand("cmd-1", "p1", 25), 1001).event

            client.receive(event)
            client.receive(event)

            assertEquals(25L, client.state.partyBerries)
            assertEquals(1L, client.state.lastEventId)
            assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(client.state))
        }

        test("out of order events are buffered and then applied in order") {
            val initial = WorldState(campaignId = "c1")
            val host = HostReplica(initial)
            val client = ClientReplica(initial)
            val e1 = host.submit(GrantBerriesCommand("cmd-1", "p1", 10), 1001).event
            val e2 = host.submit(GrantBerriesCommand("cmd-2", "p1", 20), 1002).event
            val e3 = host.submit(GrantBerriesCommand("cmd-3", "p1", 30), 1003).event

            client.receive(e3)
            assertEquals(0L, client.state.lastEventId)
            client.receive(e1)
            assertEquals(1L, client.state.lastEventId)
            client.receive(e2)

            assertEquals(3L, client.state.lastEventId)
            assertEquals(60L, client.state.partyBerries)
            assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(client.state))
        }

        test("client reconnects by delta after missing events") {
            val initial = WorldState(campaignId = "c1")
            val host = HostReplica(initial)
            val client = ClientReplica(initial)
            client.receive(host.submit(GrantBerriesCommand("cmd-1", "p1", 10), 1001).event)
            host.submit(GrantBerriesCommand("cmd-2", "p1", 20), 1002)
            host.submit(GrantBerriesCommand("cmd-3", "p1", 30), 1003)

            val plan = host.planReconnect(client.reconnectHello())
            client.applySyncPlan(plan)

            assertEquals(3L, client.state.lastEventId)
            assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(client.state))
        }

        test("divergent client hash is recovered by full snapshot") {
            val initial = WorldState(campaignId = "c1")
            val host = HostReplica(initial)
            host.submit(GrantBerriesCommand("cmd-1", "p1", 10), 1001)
            host.submit(GrantBerriesCommand("cmd-2", "p1", 20), 1002)
            val divergent = host.state.copy(partyBerries = 999)
            val client = ClientReplica(divergent)

            val plan = host.planReconnect(client.reconnectHello())
            assertEquals(true, plan is SyncPlan.FullSnapshot)
            client.applySyncPlan(plan)

            assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(client.state))
        }

        test("host restart rebuilds durable command idempotency from event history") {
            val initial = WorldState(campaignId = "c1")
            val firstHost = HostReplica(initial)
            val originalCommand = GrantBerriesCommand("loot-command", "p1", 50)
            val original = firstHost.submit(originalCommand, 1001)

            val restarted = HostReplica(
                initialState = initial,
                recoveredState = firstHost.state,
                recoveredEvents = firstHost.events,
            )
            val retry = restarted.submit(originalCommand, 9000)

            assertEquals(true, retry.wasDuplicate)
            assertEquals(original.event, retry.event)
            assertEquals(50L, restarted.state.partyBerries)
            assertEquals(1L, restarted.state.lastEventId)
        }
    }
}
