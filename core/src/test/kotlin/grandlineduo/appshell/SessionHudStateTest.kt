package grandlineduo.appshell

import grandlineduo.core.network.LanDiscoveryListener
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SessionHudStateTest {
    fun register() {
        test("host session HUD reports exact live LAN occupancy and connected slots") {
            val host = GameSessionCoordinator()
            val p2 = GameSessionCoordinator()
            val p3 = GameSessionCoordinator()
            try {
                host.startHost("Presence Host", campaignId = "presence-host")
                joinViaDiscovery(host, p2, freeUdpPort())
                joinViaDiscovery(host, p3, freeUdpPort())

                val hud = host.sessionHudState()
                assertEquals(SessionMode.HOST_COOP, hud.mode)
                assertEquals("p1", hud.localActorId)
                assertEquals(3, hud.networkConnectedCount)
                assertEquals(4, hud.maxNetworkPlayers)
                assertEquals(setOf("p1", "p2", "p3"), hud.networkConnectedPlayerIds)
                assertTrue(hud.badge.contains("3/4"))
                assertTrue(hud.badge.contains("P1"))
                assertTrue(hud.badge.contains("P2"))
                assertTrue(hud.badge.contains("P3"))
            } finally {
                p3.close()
                p2.close()
                host.close()
            }
        }

        test("client session HUD shows assigned slot and created party count without inventing socket occupancy") {
            val host = GameSessionCoordinator()
            val p2 = GameSessionCoordinator()
            val p3 = GameSessionCoordinator()
            try {
                host.startHost("Presence Client Host", campaignId = "presence-client")
                joinViaDiscovery(host, p2, freeUdpPort())
                joinViaDiscovery(host, p3, freeUdpPort())
                host.createCharacter(GameSessionCoordinatorTest.validDraft("Arlen"))
                p2.createCharacter(GameSessionCoordinatorTest.validDraft("Mira"))
                p3.refresh()

                val hud = p3.sessionHudState()
                assertEquals(SessionMode.CLIENT_COOP, hud.mode)
                assertEquals("p3", hud.localActorId)
                assertEquals(null, hud.networkConnectedCount)
                assertEquals(emptySet<String>(), hud.networkConnectedPlayerIds)
                assertEquals(setOf("p1", "p2"), hud.createdPlayerIds)
                assertTrue(hud.badge.contains("P3"))
                assertTrue(hud.badge.contains("2/4"))
            } finally {
                p3.close()
                p2.close()
                host.close()
            }
        }
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
