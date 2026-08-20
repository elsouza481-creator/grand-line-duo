package grandlineduo.game.social

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

object SocialCoordinatorTest {
    fun register() {
        test("host-authoritative social consequences create ally memory and autosave both peers") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-social-host"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-social-client"))
            val initial = initialWorld("social-coop")
            val host = HostReplica(initial)
            val coordinator = SocialCoordinator(host, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    repeat(3) { index ->
                        coordinator.applyIncident(
                            "help-lyra-$index",
                            SocialIncident(SocialIncidentType.HELPED_NPC, npcId = "lyra", factionId = "DOCKWORKERS"),
                            1_000L + index,
                        )
                    }
                    client.refresh()

                    val relation = host.state.socialState.npcRelationships.getValue("lyra")
                    assertEquals(NpcBond.ALLY, relation.bond)
                    assertEquals(60, relation.affinity)
                    assertEquals("true", host.state.worldFlags[SocialWorldFlags.HAS_ALLY])
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                }
            }
        }

        test("duplicate social command is idempotent and cannot double affinity") {
            val host = HostReplica(initialWorld("social-idempotent"))
            val coordinator = SocialCoordinator(host)
            val incident = SocialIncident(SocialIncidentType.HELPED_NPC, npcId = "lyra")
            val first = coordinator.applyIncident("same-social", incident, 2_000)
            val second = coordinator.applyIncident("same-social", incident, 2_001)
            assertEquals(first.eventId, second.eventId)
            assertEquals(20, host.state.socialState.npcRelationships.getValue("lyra").affinity)
        }

        test("dead ally stops contributing active ally world flag") {
            val host = HostReplica(initialWorld("social-death"))
            val coordinator = SocialCoordinator(host)
            repeat(3) { index ->
                coordinator.applyIncident(
                    "ally-$index",
                    SocialIncident(SocialIncidentType.HELPED_NPC, npcId = "lyra"),
                    3_000L + index,
                )
            }
            assertEquals("true", host.state.worldFlags[SocialWorldFlags.HAS_ALLY])

            coordinator.applyIncident(
                "lyra-died",
                SocialIncident(SocialIncidentType.NPC_DIED, npcId = "lyra"),
                3_100,
            )
            assertEquals(NpcStatus.DEAD, host.state.socialState.npcRelationships.getValue("lyra").status)
            assertEquals(null, host.state.worldFlags[SocialWorldFlags.HAS_ALLY])
        }


        test("social consequence survives host crash after durable append before snapshot") {
            val dir = Files.createTempDirectory("gld-social-durable-crash")
            val initial = initialWorld("social-durable")
            var crashOnce = true
            val store = grandlineduo.core.persistence.DurableCampaignStore(
                dir,
                grandlineduo.core.persistence.DurableCommitFaultInjector {
                    if (crashOnce) {
                        crashOnce = false
                        throw grandlineduo.core.persistence.SimulatedDurableCommitCrash()
                    }
                },
            )
            store.initialize(initial)
            val host = HostReplica(initial)
            val coordinator = SocialCoordinator(host, durableStore = store)

            var crashed = false
            try {
                coordinator.applyIncident(
                    "durable-ally",
                    SocialIncident(SocialIncidentType.HELPED_NPC, npcId = "lyra"),
                    5_000,
                )
            } catch (_: grandlineduo.core.persistence.SimulatedDurableCommitCrash) {
                crashed = true
            }
            assertTrue(crashed)

            val recovered = grandlineduo.core.persistence.DurableCampaignStore(dir).recover()
            assertEquals(host.state, recovered.state)
            assertEquals(20, recovered.state.socialState.npcRelationships.getValue("lyra").affinity)
            assertEquals(1, recovered.events.size)
        }

        test("social memory survives P2 snapshot restart and reconnect with identical hash") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-social-restart-host"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-social-restart-client"))
            val initial = initialWorld("social-restart")
            val host = HostReplica(initial)
            val coordinator = SocialCoordinator(host, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    coordinator.applyIncident(
                        "betray-reno",
                        SocialIncident(SocialIncidentType.BETRAYED_NPC, npcId = "reno", factionId = "RENO_PIRATES"),
                        4_000,
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

    private fun initialWorld(campaignId: String) = WorldState(
        campaignId = campaignId,
        islandId = "stormglass-cay",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 20, 20, 1_000_000),
            "p2" to PlayerState("p2", "Namiya", 20, 20, 800_000),
        ),
    )
}
