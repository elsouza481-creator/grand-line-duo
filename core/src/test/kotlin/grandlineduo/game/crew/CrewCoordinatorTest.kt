package grandlineduo.game.crew

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
import grandlineduo.game.ship.ShipEngine
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object CrewCoordinatorTest {
    fun register() {
        test("host recruits assigns and changes crew state with idempotent retry") {
            val host = HostReplica(initialWorld("crew-host"))
            val crew = CrewCoordinator(host)
            val member = member("mira", CrewRole.LOOKOUT, 4)
            val first = crew.recruit("recruit-mira", member, 1_000)
            val retry = crew.recruit("recruit-mira", member, 1_001)
            assertEquals(first.eventId, retry.eventId)
            assertEquals(1, host.state.crewState.members.size)

            crew.assignRole("assign-mira", "mira", CrewRole.NAVIGATOR, 1_002)
            crew.changeLoyalty("loyal-mira", "mira", 30, 1_003)
            crew.changeAffinity("affinity-mira", "mira", "p2", 25, 1_004)
            val updated = host.state.crewState.members.getValue("mira")
            assertEquals(CrewRole.NAVIGATOR, updated.role)
            assertEquals(50, updated.loyalty)
            assertEquals(25, updated.playerAffinity.getValue("p2"))
        }

        test("crew injury capture rescue and desertion are authoritative") {
            val host = HostReplica(initialWorld("crew-status"))
            val crew = CrewCoordinator(host)
            crew.recruit("recruit-gin", member("gin", CrewRole.GUNNER, 4, loyalty = -60), 2_000)
            crew.injure("injure-gin", "gin", 2, 2_001)
            assertEquals(CrewStatus.WOUNDED, host.state.crewState.members.getValue("gin").status)
            crew.capture("capture-gin", "gin", 2_002)
            assertEquals(CrewStatus.CAPTURED, host.state.crewState.members.getValue("gin").status)
            crew.rescue("rescue-gin", "gin", 2_003)
            assertEquals(CrewStatus.WOUNDED, host.state.crewState.members.getValue("gin").status)
            crew.heal("heal-gin", "gin", 3, 2_004)
            crew.resolveDesertion("desert-gin", "gin", severeCrisis = true, hostTimestamp = 2_005)
            assertEquals(CrewStatus.DESERTED, host.state.crewState.members.getValue("gin").status)
        }

        test("crew changes autosave and replicate to P2 with identical hash") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-crew-host"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-crew-client"))
            val initial = initialWorld("crew-coop")
            val host = HostReplica(initial)
            val coordinator = CrewCoordinator(host, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    coordinator.recruit("recruit-doc", member("lyra", CrewRole.DOCTOR, 5), 3_000)
                    coordinator.changeAffinity("doc-p2", "lyra", "p2", 40, 3_001)
                    client.refresh()
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))
                }
            }
        }

        test("crew event survives crash after durable append before snapshot") {
            val dir = Files.createTempDirectory("gld-crew-durable")
            val initial = initialWorld("crew-durable")
            var crashOnce = true
            val store = DurableCampaignStore(
                dir,
                DurableCommitFaultInjector { event ->
                    if (event.commandId == "recruit-crash" && crashOnce) {
                        crashOnce = false
                        throw SimulatedDurableCommitCrash()
                    }
                },
            )
            store.initialize(initial)
            val host = HostReplica(initial)
            val coordinator = CrewCoordinator(host, durableStore = store)

            var crashed = false
            try {
                coordinator.recruit("recruit-crash", member("brock", CrewRole.CARPENTER, 4), 4_000)
            } catch (_: SimulatedDurableCommitCrash) {
                crashed = true
            }
            assertTrue(crashed)

            val recovered = DurableCampaignStore(dir).recover()
            assertEquals(host.state, recovered.state)
            assertEquals(CrewRole.CARPENTER, recovered.state.crewState.members.getValue("brock").role)
            assertEquals(1, recovered.events.size)
        }
    }

    private fun initialWorld(campaignId: String) = WorldState(
        campaignId = campaignId,
        islandId = "open-sea",
        shipState = ShipEngine.starterShip("g", "Gull"),
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 20, 20, 1_000_000),
            "p2" to PlayerState("p2", "Namiya", 20, 20, 800_000),
        ),
    )

    private fun member(id: String, role: CrewRole, competence: Int, loyalty: Int = 20) = CrewMemberState(
        npcId = id,
        name = id,
        role = role,
        competence = competence,
        loyalty = loyalty,
    )
}
