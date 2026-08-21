package grandlineduo.appshell

import grandlineduo.game.InventoryEngine
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object FourPlayerRewardIntegrationTest {
    fun register() {
        test("four player Stormglass reward grants teammate supplies to every created remote player") {
            val host = GameSessionCoordinator()
            val p2 = GameSessionCoordinator()
            val p3 = GameSessionCoordinator()
            val p4 = GameSessionCoordinator()
            try {
                host.startHost("Reward Host", campaignId = "coord-four-reward")
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

                val beforeBandages = listOf("p2", "p3", "p4").associateWith { playerId ->
                    InventoryEngine.read(host.worldState(), playerId).items["bandage"] ?: 0
                }

                completeStormglassForParty(host, p2, p3, p4)
                host.refresh()
                p2.refresh()
                p3.refresh()
                p4.refresh()

                val authoritative = host.worldState()
                listOf("p2", "p3", "p4").forEach { playerId ->
                    val after = InventoryEngine.read(authoritative, playerId).items["bandage"] ?: 0
                    assertEquals(beforeBandages.getValue(playerId) + 2, after, "$playerId must receive the same Stormglass teammate supply reward")
                }
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

    private fun completeStormglassForParty(
        host: GameSessionCoordinator,
        p2: GameSessionCoordinator,
        p3: GameSessionCoordinator,
        p4: GameSessionCoordinator,
    ) {
        val remotes = mapOf("p2" to p2, "p3" to p3, "p4" to p4)
        var guard = 0
        while (StormglassPersistenceAdapter.decode(host.worldState()).scenario.stage != ScenarioStage.COMPLETE && guard++ < 120) {
            val restored = StormglassPersistenceAdapter.decode(host.worldState())
            val combat = restored.combat
            if (combat != null) {
                if ("p1" !in combat.lockedActions && (combat.players["p1"]?.hp ?: 0) > 0) {
                    host.submitCombatAction(CombatActionType.SETUP)
                }
                remotes.forEach { (playerId, client) ->
                    client.refresh()
                    val current = StormglassPersistenceAdapter.decode(host.worldState()).combat ?: return@forEach
                    if (playerId !in current.lockedActions && (current.players[playerId]?.hp ?: 0) > 0) {
                        client.submitCombatAction(if (playerId == "p2") CombatActionType.FINISHER else CombatActionType.ATTACK)
                    }
                }
                continue
            }

            val p1View = GamePresenter.present(host.worldState(), "p1")
            if (p1View.screen == GameScreen.STORY && p1View.actions.isNotEmpty()) {
                host.submitScenarioChoice(p1View.actions.first().id)
            }
            remotes.forEach { (playerId, client) ->
                client.refresh()
                val view = GamePresenter.present(client.worldState(), playerId)
                if (view.screen == GameScreen.STORY && view.actions.isNotEmpty()) {
                    client.submitScenarioChoice(view.actions.first().id)
                }
            }
        }
        assertTrue(guard < 120, "Four-player party must finish Stormglass before rewards are asserted")
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
