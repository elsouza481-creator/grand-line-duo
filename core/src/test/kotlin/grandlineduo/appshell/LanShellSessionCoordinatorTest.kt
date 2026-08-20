package grandlineduo.appshell

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object LanShellSessionCoordinatorTest {
    fun register() {
        test("shell coordinator advertises host and connects P2 only after real handshake") {
            val discoveryPort = freeUdpLikePort()
            val host = LanShellSessionCoordinator()
            val client = LanShellSessionCoordinator()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val hosting = host.startHost("Teste Host", campaignId = "shell-campaign")
                assertEquals(ShellMode.HOSTING, hosting.mode)
                assertTrue(hosting.port!! > 0)

                val future = executor.submit<ShellState> {
                    client.discoverAndJoin(
                        timeoutMillis = 2_000,
                        bindAddress = "127.0.0.1",
                        discoveryPort = discoveryPort,
                    )
                }
                Thread.sleep(120)
                host.advertiseOnce(InetAddress.getByName("127.0.0.1"), discoveryPort)

                val connected = future.get(3, TimeUnit.SECONDS)
                assertEquals(ShellMode.CONNECTED, connected.mode)
                assertTrue(connected.connected)
                assertEquals("shell-campaign", connected.campaignId)
                assertEquals("127.0.0.1", connected.remoteHost)

                val hostConnected = host.refreshHostPeerState()
                assertEquals(ShellMode.CONNECTED, hostConnected.mode)
                assertTrue(hostConnected.connected)
                assertEquals("shell-campaign", hostConnected.campaignId)
            } finally {
                executor.shutdownNow()
                client.close()
                host.close()
            }
        }

        test("shell coordinator returns retryable error when discovery times out") {
            val coordinator = LanShellSessionCoordinator()
            try {
                val result = coordinator.discoverAndJoin(
                    timeoutMillis = 80,
                    bindAddress = "127.0.0.1",
                    discoveryPort = freeUdpLikePort(),
                )
                assertEquals(ShellMode.ERROR, result.mode)
                assertTrue(result.canFind)
            } finally {
                coordinator.close()
            }
        }
    }

    private fun freeUdpLikePort(): Int = ServerSocket(0).use { it.localPort }
}
