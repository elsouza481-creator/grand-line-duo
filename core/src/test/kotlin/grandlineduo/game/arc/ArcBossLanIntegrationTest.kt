package grandlineduo.game.arc

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.*
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object ArcBossLanIntegrationTest {
    fun register() {
        test("P2 reconnects into locked boss round finishes combat and completes arc over TCP") {
            val initial = WorldState(
                campaignId = "arc-boss-lan",
                islandId = "ironwake-atoll",
                players = mapOf(
                    "p1" to PlayerState("p1", "Kairo", 100, 100, 9_000_000L),
                    "p2" to PlayerState("p2", "Namiya", 100, 100, 8_000_000L),
                ),
                activeArc = ArcState(
                    arcId = "ironwake:marine:boss-lan",
                    islandId = "ironwake-atoll",
                    seed = 31337L,
                    archetype = ArcArchetype.MARINE_OCCUPATION,
                    phase = ArcPhase.CLIMAX,
                    escalation = 1,
                ),
            )
            val hostDir = Files.createTempDirectory("gld-boss-lan-host")
            val clientDir = Files.createTempDirectory("gld-boss-lan-client")
            val hostStore = SnapshotStore(hostDir)
            val clientStore = SnapshotStore(clientDir)
            val host = HostReplica(initial)
            val arc = ArcCoordinator(host, snapshotStore = hostStore)
            val combat = ArcCombatCoordinator(host, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 99L, snapshotStore = hostStore)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    arc.choose("climax-p1", "p1", "draw_boss", 20_000)
                    client.sendGameplay(GameplayWireCommand.ArcChoice("climax-p2", "p2", "exploit_weakness"))
                    assertTrue(host.state.activeCombat != null)

                    combat.submitAction("round1-p1", "p1", CombatActionType.SETUP, 20_001)
                    client.disconnect()
                    client.connect()
                    assertEquals(CombatActionType.SETUP, clientReplica.state.activeCombat!!.lockedActions.getValue("p1").type)
                    client.sendGameplay(GameplayWireCommand.CombatAction("round1-p2", "p2", CombatActionType.FINISHER.name))

                    var round = 2
                    while (host.state.activeCombat != null && round <= 5) {
                        combat.submitAction("round${round}-p1", "p1", CombatActionType.SETUP, 20_000L + round * 2)
                        client.sendGameplay(
                            GameplayWireCommand.CombatAction("round${round}-p2", "p2", CombatActionType.FINISHER.name)
                        )
                        round++
                    }
                    assertEquals(null, host.state.activeCombat)
                    assertTrue(host.state.worldFlags.keys.any { it.startsWith("ARC_BOSS_DEFEATED:") })
                    assertEquals(host.state, clientReplica.state)

                    arc.choose("aftermath-p1", "p1", "spare_enemy", 21_000)
                    client.sendGameplay(GameplayWireCommand.ArcChoice("aftermath-p2", "p2", "share_evidence"))
                    assertEquals(ArcPhase.COMPLETE, host.state.activeArc!!.phase)
                    assertTrue(host.state.worldFlags.keys.any { it.startsWith("ARC_HISTORY:") && it.endsWith(":COMPLETE") })
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                }
            }
        }
    }
}
