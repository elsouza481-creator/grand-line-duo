package grandlineduo.game.ship

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object ShipCoopIntegrationTest {
    fun register() {
        test("host acquires and upgrades ship with deterministic Berries cost") {
            val initial = initialWorld("ship-upgrade").copy(partyBerries = 100_000)
            val host = HostReplica(initial)
            val ships = ShipCoordinator(host)
            ships.acquireStarterShip("acquire", "black-gull", "Black Gull", 1_000)
            val before = host.state.partyBerries
            val cost = ShipEngine.upgradeCost(host.state.shipState!!, ShipUpgrade.SAILS)
            ships.purchaseUpgrade("upgrade-sails", ShipUpgrade.SAILS, 1_001)

            assertEquals(before - cost, host.state.partyBerries)
            assertEquals(1, host.state.shipState!!.upgrades.getValue(ShipUpgrade.SAILS))
        }

        test("host repair and resupply charge only the actual restored amount and retries are idempotent") {
            val damagedShip = ShipEngine.starterShip("black-gull", "Black Gull").copy(hull = 50, supplies = 30)
            val initial = initialWorld("ship-port-services").copy(
                partyBerries = 50_000,
                shipState = damagedShip,
            )
            val host = HostReplica(initial)
            val ships = ShipCoordinator(host)

            val repair = ships.repairAtPort("repair-ten", requestedHull = 20, hostTimestamp = 1_100)
            val afterRepairBerries = host.state.partyBerries
            val retry = ships.repairAtPort("repair-ten", requestedHull = 20, hostTimestamp = 1_101)
            assertEquals(repair.eventId, retry.eventId)
            assertEquals(60, host.state.shipState!!.hull)
            assertEquals(50_000L - 10L * ShipCoordinator.REPAIR_COST_PER_HULL, afterRepairBerries)
            assertEquals(afterRepairBerries, host.state.partyBerries)

            ships.resupplyAtPort("supply-ten", requestedSupplies = 20, hostTimestamp = 1_102)
            assertEquals(40, host.state.shipState!!.supplies)
            assertEquals(
                afterRepairBerries - 10L * ShipCoordinator.SUPPLY_COST_PER_UNIT,
                host.state.partyBerries,
            )
        }

        test("P1 local and P2 TCP voyage actions resolve authoritative storm and autosave both peers") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-ship-host"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-ship-client"))
            val initial = initialWorld("ship-voyage")
            val host = HostReplica(initial)
            val ships = ShipCoordinator(host, snapshotStore = hostStore)
            val handler = StormglassGameplayCommandHandler(host, seed = 77, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    ships.acquireStarterShip("ship-acquire", "black-gull", "Black Gull", 2_000)
                    ships.beginVoyage(
                        "storm-start",
                        VoyageIncident(VoyageIncidentType.STORM, 4, 777),
                        2_001,
                    )
                    handler.handle(
                        GameplayWireCommand.VoyageAction("storm-p1", "p1", VoyageAction.HELM.name),
                        2_002,
                    )
                    val event = client.sendGameplay(
                        GameplayWireCommand.VoyageAction("storm-p2", "p2", VoyageAction.PROTECT_SUPPLIES.name),
                    )

                    assertEquals("STORM_RIDER", event.payload["meta.voyageSynergy"])
                    assertEquals(null, host.state.activeVoyage)
                    assertTrue(host.state.shipState!!.hull < host.state.shipState!!.maxHull)
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                }
            }
        }

        test("locked voyage action survives host crash after durable append before snapshot") {
            val dir = Files.createTempDirectory("gld-voyage-durable-crash")
            val initial = initialWorld("voyage-durable-crash")
            var crashOnce = true
            val durable = grandlineduo.core.persistence.DurableCampaignStore(
                dir,
                grandlineduo.core.persistence.DurableCommitFaultInjector { event ->
                    if (event.commandId == "durable-p1" && crashOnce) {
                        crashOnce = false
                        throw grandlineduo.core.persistence.SimulatedDurableCommitCrash()
                    }
                },
            )
            durable.initialize(initial)
            val host = HostReplica(initial)
            val ships = ShipCoordinator(host, durableStore = durable)
            val handler = StormglassGameplayCommandHandler(host, seed = 99, durableStore = durable)

            ships.acquireStarterShip("durable-acquire", "black-gull", "Black Gull", 4_000)
            ships.beginVoyage(
                "durable-voyage-start",
                VoyageIncident(VoyageIncidentType.SEA_KING, 3, 9_999),
                4_001,
            )

            var crashed = false
            try {
                handler.handle(
                    GameplayWireCommand.VoyageAction("durable-p1", "p1", VoyageAction.CANNONS.name),
                    4_002,
                )
            } catch (_: grandlineduo.core.persistence.SimulatedDurableCommitCrash) {
                crashed = true
            }
            assertTrue(crashed)

            val recovered = grandlineduo.core.persistence.DurableCampaignStore(dir).recover()
            assertEquals(VoyageAction.CANNONS, recovered.state.activeVoyage!!.actions["p1"])
            assertEquals(host.state, recovered.state)

            val restartedHost = HostReplica(initial, recovered.state, recovered.events)
            val restartedHandler = StormglassGameplayCommandHandler(restartedHost, seed = 99)
            val resolved = restartedHandler.handle(
                GameplayWireCommand.VoyageAction("durable-p2", "p2", VoyageAction.HELM.name),
                4_003,
            )
            assertEquals("SEA_KING_BROADSIDE", resolved.payload["meta.voyageSynergy"])
            assertEquals(null, restartedHost.state.activeVoyage)
        }

        test("P1 locked voyage action survives P2 disconnect and reconnect before resolution") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-voyage-reconnect-host"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-voyage-reconnect-client"))
            val initial = initialWorld("voyage-reconnect")
            val host = HostReplica(initial)
            val ships = ShipCoordinator(host, snapshotStore = hostStore)
            val handler = StormglassGameplayCommandHandler(host, seed = 88, snapshotStore = hostStore)
            val firstReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", firstReplica, clientStore).use { client ->
                    client.connect()
                    ships.acquireStarterShip("re-acquire", "black-gull", "Black Gull", 3_000)
                    ships.beginVoyage(
                        "sea-king-start",
                        VoyageIncident(VoyageIncidentType.SEA_KING, 3, 8_080),
                        3_001,
                    )
                    handler.handle(
                        GameplayWireCommand.VoyageAction("sea-p1", "p1", VoyageAction.CANNONS.name),
                        3_002,
                    )
                    client.refresh()
                    assertEquals(VoyageAction.CANNONS, clientStore.loadLatestValid()!!.activeVoyage!!.actions["p1"])
                    client.disconnect()
                }

                val restarted = ClientReplica(clientStore.loadLatestValid()!!)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", restarted, clientStore).use { client ->
                    client.connect()
                    val event = client.sendGameplay(
                        GameplayWireCommand.VoyageAction("sea-p2", "p2", VoyageAction.HELM.name),
                    )
                    assertEquals("SEA_KING_BROADSIDE", event.payload["meta.voyageSynergy"])
                    assertEquals(host.state, restarted.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(restarted.state))
                }
            }
        }
    }

    private fun initialWorld(campaignId: String) = WorldState(
        campaignId = campaignId,
        islandId = "open-sea",
        partyBerries = 200_000,
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 20, 20, 1_000_000),
            "p2" to PlayerState("p2", "Namiya", 20, 20, 800_000),
        ),
    )
}
