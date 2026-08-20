package grandlineduo.game.notoriety

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object NotorietyCoordinatorTest {
    fun register() {
        test("host-authoritative confirmed incident updates bounty threat and Marine response then autosaves both peers") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-notoriety-host"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-notoriety-client"))
            val initial = initialWorld().copy(
                worldFlags = mapOf(NotorietyWorldFlags.GOVERNMENT_KNOWS_LOGIA to "true"),
            )
            val host = HostReplica(initial)
            val coordinator = NotorietyCoordinator(host, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    coordinator.applyIncident(
                        commandId = "base-destroyed",
                        playerId = "p1",
                        incident = BountyIncident(BountyIncidentType.MARINE_BASE_DESTROYED, 3, IncidentVisibility.CONFIRMED),
                        marinesCanReach = true,
                        hostTimestamp = 10_000,
                    )
                    client.refresh()

                    assertEquals(72_000_000L, host.state.players.getValue("p1").bounty)
                    assertEquals(30, host.state.governmentThreatPoints)
                    assertTrue("MARINE_RESPONSE_SPECIALIST" in host.state.worldFlags)
                    assertEquals("true", host.state.worldFlags["MARINE_RESPONSE_SPECIALIST"])
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                }
            }
        }

        test("secret incident increases internal Government threat without changing public bounty") {
            val host = HostReplica(initialWorld())
            val coordinator = NotorietyCoordinator(host)
            coordinator.applyIncident(
                "secret-1",
                "p2",
                BountyIncident(BountyIncidentType.GOVERNMENT_SECRET_EXPOSED, 5, IncidentVisibility.SECRET),
                marinesCanReach = false,
                hostTimestamp = 20_000,
            )
            assertEquals(0L, host.state.players.getValue("p2").bounty)
            assertTrue(host.state.governmentThreatPoints > 0)
            assertTrue(host.state.worldFlags.keys.none { it.startsWith("MARINE_RESPONSE_") })
        }

        test("duplicate incident command does not double bounty or internal threat") {
            val host = HostReplica(initialWorld())
            val coordinator = NotorietyCoordinator(host)
            val incident = BountyIncident(BountyIncidentType.DEFEATED_MARINE_OFFICER, 2, IncidentVisibility.CONFIRMED)
            val first = coordinator.applyIncident("same-incident", "p2", incident, true, 30_000)
            val second = coordinator.applyIncident("same-incident", "p2", incident, true, 30_001)

            assertEquals(first.eventId, second.eventId)
            assertEquals(4_000_000L, host.state.players.getValue("p2").bounty)
            assertEquals(12, host.state.governmentThreatPoints)
        }


        test("notoriety event survives host crash after durable append before snapshot") {
            val dir = Files.createTempDirectory("gld-notoriety-durable-crash")
            val initial = initialWorld()
            var crashOnce = true
            val crashingStore = grandlineduo.core.persistence.DurableCampaignStore(
                dir,
                grandlineduo.core.persistence.DurableCommitFaultInjector {
                    if (crashOnce) {
                        crashOnce = false
                        throw grandlineduo.core.persistence.SimulatedDurableCommitCrash()
                    }
                },
            )
            crashingStore.initialize(initial)
            val host = HostReplica(initial)
            val coordinator = NotorietyCoordinator(host, durableStore = crashingStore)

            var crashed = false
            try {
                coordinator.applyIncident(
                    "durable-secret",
                    "p2",
                    BountyIncident(BountyIncidentType.GOVERNMENT_SECRET_EXPOSED, 4, IncidentVisibility.SECRET),
                    marinesCanReach = false,
                    hostTimestamp = 50_000,
                )
            } catch (_: grandlineduo.core.persistence.SimulatedDurableCommitCrash) {
                crashed = true
            }
            assertTrue(crashed)

            val recovered = grandlineduo.core.persistence.DurableCampaignStore(dir).recover()
            assertEquals(host.state, recovered.state)
            assertEquals(host.state.governmentThreatPoints, recovered.state.governmentThreatPoints)
            assertEquals(1, recovered.events.size)
        }

        test("notoriety state survives P2 snapshot restart and reconnect with identical hash") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-notoriety-restart-host"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-notoriety-restart-client"))
            val initial = initialWorld()
            val host = HostReplica(initial)
            val coordinator = NotorietyCoordinator(host, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    coordinator.applyIncident(
                        "officer-down",
                        "p2",
                        BountyIncident(BountyIncidentType.DEFEATED_MARINE_OFFICER, 3, IncidentVisibility.CONFIRMED),
                        true,
                        40_000,
                    )
                    client.refresh()
                    client.disconnect()
                }

                val restarted = ClientReplica(clientStore.loadLatestValid()!!)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", restarted, clientStore).use { client ->
                    client.connect()
                    assertEquals(host.state, restarted.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(restarted.state))
                }
            }
        }
    }

    private fun initialWorld() = WorldState(
        campaignId = "notoriety-coop",
        islandId = "stormglass-cay",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 20, 20, 60_000_000),
            "p2" to PlayerState("p2", "Namiya", 20, 20, 0),
        ),
    )
}
