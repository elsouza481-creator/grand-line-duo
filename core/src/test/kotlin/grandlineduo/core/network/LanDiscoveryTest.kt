package grandlineduo.core.network

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.net.InetAddress

object LanDiscoveryTest {
    fun register() {
        test("discovery advertisement codec round trips and rejects corruption") {
            val ad = LanDiscoveryAdvertisement(
                protocolVersion = PROTOCOL_VERSION,
                sessionId = "room-a7k2",
                campaignId = "campaign-1",
                hostName = "Kairo's crew",
                tcpPort = 43210,
                currentPlayers = 3,
                maxPlayers = 4,
            )
            val bytes = LanDiscoveryCodec.encode(ad)
            assertEquals(ad, LanDiscoveryCodec.decode(bytes))

            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x33).toByte()
            var failed = false
            try { LanDiscoveryCodec.decode(bytes) } catch (_: LanDiscoveryException) { failed = true }
            assertTrue(failed)
        }

        test("protocol v6 discovery enforces four player room capacity") {
            assertEquals(6, PROTOCOL_VERSION)
            val valid = LanDiscoveryAdvertisement(
                protocolVersion = PROTOCOL_VERSION,
                sessionId = "room-capacity",
                campaignId = "campaign-capacity",
                hostName = "Capacity Crew",
                tcpPort = 43211,
                currentPlayers = 4,
                maxPlayers = 4,
            )
            assertEquals(4, valid.currentPlayers)
            assertEquals(4, valid.maxPlayers)

            var zeroRejected = false
            try {
                valid.copy(currentPlayers = 0)
            } catch (_: IllegalArgumentException) {
                zeroRejected = true
            }
            assertTrue(zeroRejected)

            var overflowRejected = false
            try {
                valid.copy(currentPlayers = 5)
            } catch (_: IllegalArgumentException) {
                overflowRejected = true
            }
            assertTrue(overflowRejected)

            var wrongCapacityRejected = false
            try {
                valid.copy(maxPlayers = 3, currentPlayers = 3)
            } catch (_: IllegalArgumentException) {
                wrongCapacityRejected = true
            }
            assertTrue(wrongCapacityRejected)
        }

        test("P2 discovers P1 advertisement over real UDP loopback") {
            LanDiscoveryListener(bindAddress = "127.0.0.1", port = 0).use { listener ->
                listener.start()
                val ad = LanDiscoveryAdvertisement(
                    protocolVersion = PROTOCOL_VERSION,
                    sessionId = "stormglass-room",
                    campaignId = "campaign-udp",
                    hostName = "Stormglass Crew",
                    tcpPort = 45678,
                    currentPlayers = 2,
                    maxPlayers = 4,
                )
                LanDiscoveryAdvertiser(
                    targetAddress = InetAddress.getByName("127.0.0.1"),
                    targetPort = listener.boundPort,
                ).use { advertiser ->
                    advertiser.send(ad)
                }

                val found = listener.receive(timeoutMillis = 1500)
                    ?: error("advertisement not received")
                assertEquals(ad, found.advertisement)
                assertEquals("127.0.0.1", found.sourceAddress.hostAddress)
            }
        }

        test("listener ignores advertisements from incompatible protocol") {
            LanDiscoveryListener(bindAddress = "127.0.0.1", port = 0).use { listener ->
                listener.start()
                val ad = LanDiscoveryAdvertisement(
                    protocolVersion = PROTOCOL_VERSION + 1,
                    sessionId = "future-room",
                    campaignId = "future-campaign",
                    hostName = "Future Crew",
                    tcpPort = 45679,
                    currentPlayers = 1,
                    maxPlayers = 4,
                )
                LanDiscoveryAdvertiser(
                    targetAddress = InetAddress.getByName("127.0.0.1"),
                    targetPort = listener.boundPort,
                ).use { it.send(ad) }
                assertEquals(null, listener.receive(timeoutMillis = 250))
            }
        }
    }
}
