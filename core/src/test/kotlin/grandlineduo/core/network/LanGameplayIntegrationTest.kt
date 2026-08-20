package grandlineduo.core.network

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.DurableCommitFaultInjector
import grandlineduo.core.persistence.SimulatedDurableCommitCrash
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.scenario.StormglassCayScenario
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object LanGameplayIntegrationTest {
    fun register() {
        test("P2 scenario choice travels over LAN and converges with host state") {
            val setup = setupSession("lan-game-1")
            setup.server.use { server ->
                server.start()
                setup.client().use { client ->
                    client.connect()
                    setup.handler.handle(
                        GameplayWireCommand.ScenarioChoice("p1-arrival", "p1", "help_dockworker"),
                        1000,
                    )

                    val event = client.sendGameplay(
                        GameplayWireCommand.ScenarioChoice("p2-arrival", "p2", "shadow_courier")
                    )

                    assertEquals(EventTypeForTest.WORLD_REPLACED, EventTypeForTest.from(event.eventType.name))
                    assertEquals(setup.host.state, setup.clientReplica.state)
                    val restored = StormglassPersistenceAdapter.decode(setup.clientReplica.state)
                    assertEquals(ScenarioStage.INVESTIGATION, restored.scenario.stage)
                    assertTrue("marine_manifest" in restored.scenario.privateKnowledge["p2"].orEmpty())
                }
            }
        }

        test("retrying P2 gameplay command after reconnect is idempotent") {
            val setup = setupSession("lan-game-2")
            setup.server.use { server ->
                server.start()
                setup.client().use { client ->
                    client.connect()
                    setup.handler.handle(
                        GameplayWireCommand.ScenarioChoice("p1-arrival", "p1", "help_dockworker"),
                        1000,
                    )
                    val request = GameplayWireCommand.ScenarioChoice("p2-once", "p2", "shadow_courier")
                    val first = client.sendGameplay(request)
                    client.disconnect()
                    client.connect()
                    val retry = client.sendGameplay(request)

                    assertEquals(first.eventId, retry.eventId)
                    assertEquals(2L, setup.host.state.lastEventId)
                    assertEquals(setup.host.state, setup.clientReplica.state)
                }
            }
        }

        test("host and P2 autosave the same authoritative gameplay progress") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-host-autosave"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-client-autosave"))
            val setup = setupSession("lan-game-save", hostStore, clientStore)
            setup.server.use { server ->
                server.start()
                setup.client().use { client ->
                    client.connect()
                    setup.handler.handle(
                        GameplayWireCommand.ScenarioChoice("save-p1", "p1", "help_dockworker"),
                        1000,
                    )
                    client.sendGameplay(
                        GameplayWireCommand.ScenarioChoice("save-p2", "p2", "shadow_courier")
                    )

                    assertEquals(setup.host.state, hostStore.loadLatestValid())
                    assertEquals(setup.clientReplica.state, clientStore.loadLatestValid())
                    assertEquals(hostStore.loadLatestValid(), clientStore.loadLatestValid())
                }
            }
        }

        test("host crash after event append recovers gameplay and P2 continues over LAN") {
            val dir = Files.createTempDirectory("gld-live-durable")
            val initial = initialWorld("lan-game-crash")
            var crashOnce = true
            val crashingStore = DurableCampaignStore(dir, DurableCommitFaultInjector {
                if (crashOnce) {
                    crashOnce = false
                    throw SimulatedDurableCommitCrash()
                }
            })
            crashingStore.initialize(initial)
            val firstHost = HostReplica(initial)
            val firstHandler = StormglassGameplayCommandHandler(
                firstHost, seed = 77, durableStore = crashingStore,
            )

            var crashed = false
            try {
                firstHandler.handle(
                    GameplayWireCommand.ScenarioChoice("crash-p1", "p1", "help_dockworker"),
                    1000,
                )
            } catch (_: SimulatedDurableCommitCrash) { crashed = true }
            assertTrue(crashed)

            val restartedStore = DurableCampaignStore(dir)
            val recovered = restartedStore.recover()
            val restartedHost = HostReplica(initial, recovered.state, recovered.events)
            val restartedHandler = StormglassGameplayCommandHandler(
                restartedHost, seed = 77, durableStore = restartedStore,
            )
            LanHostServer(restartedHost, port = 0, gameplayCommandHandler = restartedHandler).use { server ->
                server.start()
                val clientReplica = ClientReplica(initial)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    client.sendGameplay(
                        GameplayWireCommand.ScenarioChoice("after-crash-p2", "p2", "shadow_courier")
                    )
                    assertEquals(restartedHost.state, clientReplica.state)
                    assertEquals(2L, restartedHost.state.lastEventId)
                    assertEquals(ScenarioStage.INVESTIGATION, StormglassPersistenceAdapter.decode(restartedHost.state).scenario.stage)
                }
            }
        }

        test("P2 combat action over LAN resolves cooperative combo after P1 locks setup") {
            val setup = setupSession("lan-game-3")
            setup.server.use { server ->
                server.start()
                setup.client().use { client ->
                    client.connect()
                    advanceToMiniboss(setup, client)
                    setup.handler.handle(
                        GameplayWireCommand.CombatAction("p1-combat", "p1", CombatActionType.SETUP.name),
                        2000,
                    )

                    val event = client.sendGameplay(
                        GameplayWireCommand.CombatAction("p2-combat", "p2", CombatActionType.FINISHER.name)
                    )

                    assertEquals("true", event.payload["meta.coopCombo"])
                    assertEquals(setup.host.state, setup.clientReplica.state)
                    val combat = StormglassPersistenceAdapter.decode(setup.clientReplica.state).combat
                        ?: error("combat should remain active after first round")
                    assertEquals(2, combat.round)
                    assertTrue(combat.enemy.hp < combat.enemy.maxHp)
                }
            }
        }
    }

    private data class Setup(
        val host: HostReplica,
        val handler: StormglassGameplayCommandHandler,
        val server: LanHostServer,
        val clientReplica: ClientReplica,
        val clientStore: SnapshotStore? = null,
    ) {
        fun client(): LanClientConnection = LanClientConnection(
            "127.0.0.1", server.boundPort, "p2", clientReplica, snapshotStore = clientStore,
        )
    }

    private fun setupSession(
        campaignId: String,
        hostStore: SnapshotStore? = null,
        clientStore: SnapshotStore? = null,
    ): Setup {
        val initial = initialWorld(campaignId)
        val host = HostReplica(initial)
        val handler = StormglassGameplayCommandHandler(host, seed = 77, snapshotStore = hostStore)
        val server = LanHostServer(host, port = 0, gameplayCommandHandler = handler)
        val clientReplica = ClientReplica(initial)
        return Setup(host, handler, server, clientReplica, clientStore)
    }


    private fun initialWorld(campaignId: String): WorldState {
        val scenario = StormglassCayScenario().initialState()
        val base = WorldState(
            campaignId = campaignId,
            islandId = "stormglass-cay",
            players = mapOf(
                "p1" to PlayerState("p1", "Kairo", 60, 60, 0),
                "p2" to PlayerState("p2", "Namiya", 55, 55, 0),
            ),
        )
        return StormglassPersistenceAdapter.encode(base, scenario, null)
    }

    private fun advanceToMiniboss(setup: Setup, client: LanClientConnection) {
        setup.handler.handle(GameplayWireCommand.ScenarioChoice("a1", "p1", "help_dockworker"), 1)
        client.sendGameplay(GameplayWireCommand.ScenarioChoice("a2", "p2", "shadow_courier"))
        setup.handler.handle(GameplayWireCommand.ScenarioChoice("i1", "p1", "question_dockworker"), 2)
        client.sendGameplay(GameplayWireCommand.ScenarioChoice("i2", "p2", "reveal_manifest"))
        setup.handler.handle(GameplayWireCommand.ScenarioChoice("w1", "p1", "set_ambush"), 3)
        client.sendGameplay(GameplayWireCommand.ScenarioChoice("w2", "p2", "enter_warehouse"))
        val restored = StormglassPersistenceAdapter.decode(setup.host.state)
        assertEquals(ScenarioStage.MINIBOSS, restored.scenario.stage)
        assertTrue(restored.combat != null)
    }

    // Avoid importing a not-yet-existing enum member at test compile time in the assertion message.
    private enum class EventTypeForTest { WORLD_REPLACED;
        companion object { fun from(name: String) = valueOf(name) }
    }
}
