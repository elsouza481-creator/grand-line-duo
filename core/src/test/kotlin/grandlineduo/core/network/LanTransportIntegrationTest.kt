package grandlineduo.core.network

import grandlineduo.core.commands.GrantBerriesCommand
import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object LanTransportIntegrationTest {
    fun register() {
        test("LAN client connects and receives missing authoritative state") {
            val initial = WorldState(campaignId = "lan-1")
            val hostReplica = HostReplica(initial)
            hostReplica.submit(GrantBerriesCommand("host-before-connect", "p1", 40), 1000)
            LanHostServer(hostReplica, port = 0).use { server ->
                server.start()
                val clientReplica = ClientReplica(initial)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    assertEquals(
                        CanonicalStateHasher.hash(hostReplica.state),
                        CanonicalStateHasher.hash(clientReplica.state),
                    )
                }
            }
        }

        test("LAN command executes on host and authoritative event updates client") {
            val initial = WorldState(campaignId = "lan-2")
            val hostReplica = HostReplica(initial)
            LanHostServer(hostReplica, port = 0).use { server ->
                server.start()
                val clientReplica = ClientReplica(initial)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    client.send(GrantBerriesCommand("remote-loot", "p2", 125))

                    assertEquals(125L, hostReplica.state.partyBerries)
                    assertEquals(hostReplica.state, clientReplica.state)
                }
            }
        }

        test("LAN client reconnect catches up events created while offline") {
            val initial = WorldState(campaignId = "lan-3")
            val hostReplica = HostReplica(initial)
            LanHostServer(hostReplica, port = 0).use { server ->
                server.start()
                val clientReplica = ClientReplica(initial)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    client.send(GrantBerriesCommand("cmd-1", "p2", 10))
                    client.disconnect()
                    hostReplica.submit(GrantBerriesCommand("cmd-2", "p1", 20), 2000)
                    hostReplica.submit(GrantBerriesCommand("cmd-3", "p1", 30), 2001)

                    client.connect()

                    assertEquals(hostReplica.state, clientReplica.state)
                    assertEquals(60L, clientReplica.state.partyBerries)
                }
            }
        }

        test("LAN host keeps an authenticated session alive while it is idle") {
            val initial = WorldState(campaignId = "lan-idle")
            val hostReplica = HostReplica(initial)
            LanHostServer(hostReplica, port = 0, handshakeTimeoutMillis = 100).use { server ->
                server.start()
                val clientReplica = ClientReplica(initial)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    Thread.sleep(250)
                    hostReplica.submit(GrantBerriesCommand("idle-host-change", "p1", 15), 3000)
                    client.refresh()

                    assertEquals(hostReplica.state, clientReplica.state)
                    assertEquals(15L, clientReplica.state.partyBerries)
                }
            }
        }

        test("LAN host rejects a peer other than configured P2") {
            val initial = WorldState(campaignId = "lan-4")
            val hostReplica = HostReplica(initial)
            LanHostServer(hostReplica, port = 0, allowedClientId = "p2").use { server ->
                server.start()
                val clientReplica = ClientReplica(initial)
                val intruder = LanClientConnection(
                    "127.0.0.1",
                    server.boundPort,
                    "p3",
                    clientReplica,
                )
                var rejected = false
                try {
                    intruder.connect()
                } catch (_: LanSessionException) {
                    rejected = true
                }
                assertEquals(true, rejected)
                intruder.close()
            }
        }

        test("LAN host keeps p2 p3 and p4 connected concurrently and reconnects one peer independently") {
            val initial = WorldState(campaignId = "lan-four-player-transport")
            val hostReplica = HostReplica(initial)
            LanHostServer(hostReplica, port = 0).use { server ->
                server.start()
                val p2Replica = ClientReplica(initial)
                val p3Replica = ClientReplica(initial)
                val p4Replica = ClientReplica(initial)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", p2Replica).use { p2 ->
                    LanClientConnection("127.0.0.1", server.boundPort, "p3", p3Replica).use { p3 ->
                        LanClientConnection("127.0.0.1", server.boundPort, "p4", p4Replica).use { p4 ->
                            p2.connect()
                            p3.connect()
                            p4.connect()

                            assertEquals(setOf("p2", "p3", "p4"), server.activeClientIds)
                            assertEquals(3, server.activeClientCount)

                            hostReplica.submit(GrantBerriesCommand("four-host-change", "p1", 77), 4000)
                            p2.refresh()
                            p3.refresh()
                            p4.refresh()
                            assertEquals(hostReplica.state, p2Replica.state)
                            assertEquals(hostReplica.state, p3Replica.state)
                            assertEquals(hostReplica.state, p4Replica.state)

                            p3.disconnect()
                            Thread.sleep(25)
                            assertEquals(setOf("p2", "p4"), server.activeClientIds)

                            p3.connect()
                            assertEquals(setOf("p2", "p3", "p4"), server.activeClientIds)
                            assertEquals(3, server.activeClientCount)
                        }
                    }
                }
            }
        }
    }
}
