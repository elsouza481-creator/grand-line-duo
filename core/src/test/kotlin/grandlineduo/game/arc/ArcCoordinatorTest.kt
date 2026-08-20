package grandlineduo.game.arc

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.DurableCommitFaultInjector
import grandlineduo.core.persistence.SimulatedDurableCommitCrash
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object ArcCoordinatorTest {
    fun register() {
        test("host starts one authoritative arc and duplicate start is idempotent") {
            val host = HostReplica(world("arc-start"))
            val coordinator = ArcCoordinator(host)
            val context = context(host.state, 10L)
            val first = coordinator.startArc("arc-start-1", context, 1_000)
            val retry = coordinator.startArc("arc-start-1", context, 1_001)
            assertEquals(first.eventId, retry.eventId)
            assertTrue(host.state.activeArc != null)
            assertEquals(1L, host.state.lastEventId)
        }

        test("host applies player arc choice as authoritative state") {
            val host = HostReplica(world("arc-choice"))
            val coordinator = ArcCoordinator(host)
            coordinator.startArc("start", context(host.state, 20L), 2_000)
            coordinator.choose("p2-clue", "p2", "shadow_authority", 2_001)
            assertTrue(host.state.activeArc!!.privateClues.getValue("p2").isNotEmpty())
            assertEquals(2L, host.state.lastEventId)
        }

        test("invalid arc choice does not mutate authoritative state") {
            val host = HostReplica(world("arc-invalid"))
            val coordinator = ArcCoordinator(host)
            coordinator.startArc("start", context(host.state, 30L), 3_000)
            val before = CanonicalStateHasher.hash(host.state)
            var rejected = false
            try {
                coordinator.choose("bad", "p1", "not-a-choice", 3_001)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(before, CanonicalStateHasher.hash(host.state))
        }

        test("duplicate player arc choice cannot execute twice") {
            val host = HostReplica(world("arc-idempotent"))
            val coordinator = ArcCoordinator(host)
            coordinator.startArc("start", context(host.state, 40L), 4_000)
            val first = coordinator.choose("same-choice", "p1", "approach_openly", 4_001)
            val retry = coordinator.choose("same-choice", "p1", "approach_openly", 4_002)
            assertEquals(first.eventId, retry.eventId)
            assertEquals(1, host.state.activeArc!!.escalation)
        }

        test("arc changes autosave on host and P2 after refresh") {
            val initial = world("arc-coop")
            val hostDir = Files.createTempDirectory("gld-arc-host")
            val clientDir = Files.createTempDirectory("gld-arc-client")
            val hostStore = SnapshotStore(hostDir)
            val clientStore = SnapshotStore(clientDir)
            val host = HostReplica(initial)
            val coordinator = ArcCoordinator(host, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    coordinator.startArc("start", context(host.state, 50L), 5_000)
                    coordinator.choose("p1-choice", "p1", "help_locals", 5_001)
                    client.refresh()
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                }
            }
        }

        test("arc event survives crash after journal append before snapshot") {
            val dir = Files.createTempDirectory("gld-arc-durable")
            val initial = world("arc-durable")
            var crashOnce = true
            val store = DurableCampaignStore(
                dir,
                DurableCommitFaultInjector { event ->
                    if (event.commandId == "p2-crash" && crashOnce) {
                        crashOnce = false
                        throw SimulatedDurableCommitCrash()
                    }
                },
            )
            store.initialize(initial)
            val host = HostReplica(initial)
            val coordinator = ArcCoordinator(host, durableStore = store)
            coordinator.startArc("start", context(host.state, 60L), 6_000)

            var crashed = false
            try {
                coordinator.choose("p2-crash", "p2", "shadow_authority", 6_001)
            } catch (_: SimulatedDurableCommitCrash) {
                crashed = true
            }
            assertTrue(crashed)

            val recovered = DurableCampaignStore(dir).recover()
            assertEquals(host.state, recovered.state)
            assertTrue(recovered.state.activeArc!!.privateClues.getValue("p2").isNotEmpty())
            assertEquals(2, recovered.events.size)
        }
    }

    private fun world(id: String) = WorldState(
        campaignId = id,
        islandId = "ironwake-atoll",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 30, 30, 8_000_000L),
            "p2" to PlayerState("p2", "Namiya", 30, 30, 7_000_000L),
        ),
        worldFlags = mapOf("MARINE_RESPONSE_CAPTAIN" to "true"),
    )

    private fun context(world: WorldState, seed: Long) = ArcDirectorBridge.contextFor(world, seed, setOf("MARINES"))
}
