package grandlineduo.appshell

import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object FourPlayerReconnectIntegrationTest {
    fun register() {
        test("P3 reconnects into its assigned slot and completes a pending four player decision without duplication") {
            val host = GameSessionCoordinator()
            val p2 = GameSessionCoordinator()
            val p3 = GameSessionCoordinator()
            val p4 = GameSessionCoordinator()
            try {
                host.startHost("Reconnect Host", campaignId = "coord-four-reconnect")
                joinViaDiscovery(host, p2, freeUdpPort())
                joinViaDiscovery(host, p3, freeUdpPort())
                joinViaDiscovery(host, p4, freeUdpPort())
                host.createCharacter(GameSessionCoordinatorTest.validDraft("Arlen"))
                p2.createCharacter(GameSessionCoordinatorTest.validDraft("Mira"))
                p3.createCharacter(GameSessionCoordinatorTest.validDraft("Rika"))
                p4.createCharacter(GameSessionCoordinatorTest.validDraft("Bram"))
                p2.refresh()
                p3.refresh()
                p4.refresh()

                submitFirstStoryChoice(host, "p1")
                p2.refresh()
                submitFirstStoryChoice(p2, "p2")
                p4.refresh()
                submitFirstStoryChoice(p4, "p4")

                val pending = StormglassPersistenceAdapter.decode(host.worldState()).scenario
                assertEquals(ScenarioStage.ARRIVAL, pending.stage)
                assertEquals(setOf("p1", "p2", "p4"), pending.actedThisStage)

                val replacementReplica = ClientReplica(WorldState(campaignId = host.worldState().campaignId))
                LanClientConnection(
                    host = "127.0.0.1",
                    port = host.boundPort,
                    peerId = "p3",
                    replica = replacementReplica,
                ).use { replacement ->
                    replacement.connect()
                    var detectedDrop = false
                    try {
                        p3.refresh()
                    } catch (_: Exception) {
                        detectedDrop = true
                    }
                    assertTrue(detectedDrop, "P3 must observe that its original socket was replaced")

                    val beforeReconnectEventId = host.worldState().lastEventId
                    p3.reconnect()
                    assertEquals("p3", p3.actorId)
                    assertEquals(host.worldState(), p3.worldState())
                    assertEquals(beforeReconnectEventId, host.worldState().lastEventId, "Reconnect must not create gameplay events")

                    val restoredPending = StormglassPersistenceAdapter.decode(p3.worldState()).scenario
                    assertEquals(ScenarioStage.ARRIVAL, restoredPending.stage)
                    assertEquals(setOf("p1", "p2", "p4"), restoredPending.actedThisStage)

                    submitFirstStoryChoice(p3, "p3")
                }

                p2.refresh()
                p4.refresh()
                val authoritative = host.worldState()
                assertEquals(ScenarioStage.INVESTIGATION, StormglassPersistenceAdapter.decode(authoritative).scenario.stage)
                assertEquals(authoritative, p2.worldState())
                assertEquals(authoritative, p3.worldState())
                assertEquals(authoritative, p4.worldState())
            } finally {
                p4.close()
                p3.close()
                p2.close()
                host.close()
            }
        }
    }

    private fun submitFirstStoryChoice(session: GameSessionCoordinator, playerId: String) {
        val view = GamePresenter.present(session.worldState(), playerId)
        assertEquals(GameScreen.STORY, view.screen)
        val action = view.actions.firstOrNull() ?: error("$playerId must have a story choice")
        session.submitScenarioChoice(action.id)
    }

    private fun joinViaDiscovery(host: GameSessionCoordinator, client: GameSessionCoordinator, discoveryPort: Int) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                client.discoverAndJoin(
                    timeoutMillis = 2_000,
                    bindAddress = "127.0.0.1",
                    discoveryPort = discoveryPort,
                )
            }
            Thread.sleep(100)
            host.advertiseOnce(InetAddress.getByName("127.0.0.1"), discoveryPort)
            future.get(3, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }
}
