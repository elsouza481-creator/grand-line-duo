package grandlineduo.game.arc

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.*
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object ArcLanIntegrationTest {
    fun register() {
        test("arc choice wire command round trips through the LAN codec") {
            val command = GameplayWireCommand.ArcChoice("arc-p2-1", "p2", "shadow_authority")
            val message = WireMessage.GameplayCommand(command)
            assertEquals(message, WireCodec.decodeFrame(WireCodec.encodeFrame(message)))
        }

        test("P2 disconnects reconnects reveals private intel and reaches persistent boss over TCP") {
            val initial = world("arc-lan")
            val hostDir = Files.createTempDirectory("gld-arc-lan-host")
            val clientDir = Files.createTempDirectory("gld-arc-lan-client")
            val hostStore = SnapshotStore(hostDir)
            val clientStore = SnapshotStore(clientDir)
            val host = HostReplica(initial)
            val arc = ArcCoordinator(host, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 99L, snapshotStore = hostStore)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    arc.startArc(
                        "arc-start",
                        ArcDirectorBridge.contextFor(host.state, 123L, setOf("MARINES")),
                        10_000,
                    )
                    arc.choose("arrival-p1", "p1", "help_locals", 10_001)
                    client.sendGameplay(GameplayWireCommand.ArcChoice("arrival-p2", "p2", "shadow_authority"))
                    assertEquals(ArcPhase.INVESTIGATION, host.state.activeArc!!.phase)
                    assertTrue(host.state.activeArc!!.privateClues.getValue("p2").isNotEmpty())

                    client.disconnect()
                    arc.choose("investigate-p1", "p1", "question_contacts", 10_002)
                    client.connect()
                    client.sendGameplay(GameplayWireCommand.ArcChoice("reveal-p2", "p2", "reveal_intel"))
                    assertEquals(ArcPhase.ESCALATION, host.state.activeArc!!.phase)
                    assertTrue(host.state.activeArc!!.sharedFlags.any { it.startsWith("INTEL_REVEALED:") })

                    arc.choose("escalate-p1", "p1", "secure_escape", 10_003)
                    client.sendGameplay(GameplayWireCommand.ArcChoice("escalate-p2", "p2", "sabotage_support"))
                    assertEquals(ArcPhase.CLIMAX, host.state.activeArc!!.phase)

                    arc.choose("climax-p1", "p1", "draw_boss", 10_004)
                    client.sendGameplay(GameplayWireCommand.ArcChoice("climax-p2", "p2", "exploit_weakness"))
                    assertEquals(ArcPhase.AFTERMATH, host.state.activeArc!!.phase)
                    assertTrue(host.state.activeCombat != null)
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                }
            }
        }
    }

    private fun world(id: String) = WorldState(
        campaignId = id,
        islandId = "ironwake-atoll",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 32, 32, 9_000_000L),
            "p2" to PlayerState("p2", "Namiya", 28, 28, 8_000_000L),
        ),
        worldFlags = mapOf("MARINE_RESPONSE_CAPTAIN" to "true"),
    )
}
