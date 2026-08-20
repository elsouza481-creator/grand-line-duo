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

        test("LAN host rejects a peer other than configured P2") {
            val initial = WorldState(campaignId = "lan-4")
            val hostReplica = HostReplica(initial)
            LanHostServer(hostReplica, port = 0, allowedClientId = "p2").use { server ->
                server.start()
                val intruder = LanClientConnection(
                    "127.0.0.1",
                    server.boundPort,
                    "p3",
                    ClientReplica(initial),
                )
                var failed = false
                try { intruder.connect() } catch (_: LanSessionException) { failed = true }
                finally { intruder.close() }
                assertEquals(true, failed)
            }
        }
    }
}
