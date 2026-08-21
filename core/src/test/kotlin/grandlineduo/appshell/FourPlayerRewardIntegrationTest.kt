package grandlineduo.appshell

import grandlineduo.game.InventoryEngine
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcEngine
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.ship.VoyageAction
import grandlineduo.game.world.ExplorationDirection
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
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
                createFourPlayerParty(host, p2, p3, p4)

                val beforeBandages = listOf("p2", "p3", "p4").associateWith { playerId ->
                    InventoryEngine.read(host.worldState(), playerId).items["bandage"] ?: 0
                }

                completeStormglassForParty(host, p2, p3, p4)
                host.refresh()
                refreshRemotes(p2, p3, p4)

                val authoritative = host.worldState()
                listOf("p2", "p3", "p4").forEach { playerId ->
                    val after = InventoryEngine.read(authoritative, playerId).items["bandage"] ?: 0
                    assertEquals(beforeBandages.getValue(playerId) + 2, after, "$playerId must receive the same Stormglass teammate supply reward")
                }
                assertReplicasConverged(authoritative, p2, p3, p4)
            } finally {
                p4.close()
                p3.close()
                p2.close()
                host.close()
            }
        }

        test("four player main arc reward grants teammate rations to every created remote player") {
            val host = GameSessionCoordinator()
            val p2 = GameSessionCoordinator()
            val p3 = GameSessionCoordinator()
            val p4 = GameSessionCoordinator()
            try {
                host.startHost("Arc Reward Host", campaignId = "coord-four-arc-reward")
                joinViaDiscovery(host, p2, freeUdpPort())
                joinViaDiscovery(host, p3, freeUdpPort())
                joinViaDiscovery(host, p4, freeUdpPort())
                createFourPlayerParty(host, p2, p3, p4)

                completeStormglassForParty(host, p2, p3, p4)
                host.refresh()
                assertTrue(moveP1ToDock(host), "P1 must reach the dock before starting the first four-player arc")
                host.advanceCampaign()
                host.submitVoyageAction(VoyageAction.HELM)
                p2.refresh()
                p2.submitVoyageAction(VoyageAction.PROTECT_SUPPLIES)
                p3.refresh()
                p3.submitVoyageAction(VoyageAction.REPAIR)
                p4.refresh()
                p4.submitVoyageAction(VoyageAction.LOOKOUT)
                host.refresh()
                refreshRemotes(p2, p3, p4)
                assertTrue(host.worldState().activeArc != null, "First destination must start a four-player main arc")

                val beforeRations = listOf("p2", "p3", "p4").associateWith { playerId ->
                    InventoryEngine.read(host.worldState(), playerId).items["ration"] ?: 0
                }

                completeActiveArcForParty(host, p2, p3, p4)
                host.refresh()
                refreshRemotes(p2, p3, p4)

                val authoritative = host.worldState()
                listOf("p2", "p3", "p4").forEach { playerId ->
                    val after = InventoryEngine.read(authoritative, playerId).items["ration"] ?: 0
                    assertEquals(beforeRations.getValue(playerId) + 2, after, "$playerId must receive the same main-arc teammate supply reward")
                }
                assertReplicasConverged(authoritative, p2, p3, p4)
            } finally {
                p4.close()
                p3.close()
                p2.close()
                host.close()
            }
        }
    }

    private fun createFourPlayerParty(
        host: GameSessionCoordinator,
        p2: GameSessionCoordinator,
        p3: GameSessionCoordinator,
        p4: GameSessionCoordinator,
    ) {
        host.createCharacter(GameSessionCoordinatorTest.validDraft("Arlen"))
        p2.createCharacter(GameSessionCoordinatorTest.validDraft("Mira"))
        p3.createCharacter(GameSessionCoordinatorTest.validDraft("Rika"))
        p4.createCharacter(GameSessionCoordinatorTest.validDraft("Bram"))
        refreshRemotes(p2, p3, p4)
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

    private fun completeActiveArcForParty(
        host: GameSessionCoordinator,
        p2: GameSessionCoordinator,
        p3: GameSessionCoordinator,
        p4: GameSessionCoordinator,
    ) {
        val sessions = linkedMapOf("p1" to host, "p2" to p2, "p3" to p3, "p4" to p4)
        var guard = 0
        while (host.worldState().activeArc?.phase != ArcPhase.COMPLETE && guard++ < 200) {
            val combat = host.worldState().activeCombat
            if (combat != null) {
                assertTrue(combat.status != CombatStatus.DEFEAT, "Four-player party must not be defeated while proving arc rewards")
                sessions.forEach { (playerId, session) ->
                    if (playerId != "p1") session.refresh()
                    val current = host.worldState().activeCombat ?: return@forEach
                    if ((current.players[playerId]?.hp ?: 0) <= 0 || playerId in current.lockedActions) return@forEach
                    val action = when (playerId) {
                        "p1" -> CombatActionType.SETUP
                        "p2" -> CombatActionType.FINISHER
                        else -> CombatActionType.ATTACK
                    }
                    session.submitCombatAction(action)
                }
                continue
            }

            sessions.forEach { (playerId, session) ->
                if (host.worldState().activeCombat != null) return@forEach
                if (playerId != "p1") session.refresh()
                val arc = host.worldState().activeArc ?: return@forEach
                if (arc.phase == ArcPhase.COMPLETE || playerId in arc.actedThisPhase) return@forEach
                val choice = ArcEngine.view(arc, playerId).choices.firstOrNull() ?: return@forEach
                session.submitArcChoice(choice.id)
            }
        }
        assertTrue(guard < 200, "Four-player party must finish the main arc before rewards are asserted")
        assertEquals(ArcPhase.COMPLETE, host.worldState().activeArc?.phase)
    }

    private fun moveP1ToDock(host: GameSessionCoordinator): Boolean {
        var guard = 0
        while (ExplorationEngine.interactionAt(host.worldState(), "p1") != ExplorationInteraction.DOCK && guard++ < 40) {
            if (host.worldState().activeCombat != null) return false
            val world = host.worldState()
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val current = ExplorationEngine.position(world, "p1")
            val dock = map.interactions.entries.first { it.value == ExplorationInteraction.DOCK }.key
            val direction = when {
                current.x < dock.x -> ExplorationDirection.EAST
                current.x > dock.x -> ExplorationDirection.WEST
                current.y < dock.y -> ExplorationDirection.SOUTH
                else -> ExplorationDirection.NORTH
            }
            host.submitWorldAction("EXPLORE_MOVE", direction.name, 999)
            if (host.worldState().activeCombat != null) return false
        }
        assertTrue(guard < 40, "P1 must be able to walk to the physical dock")
        return true
    }

    private fun refreshRemotes(
        p2: GameSessionCoordinator,
        p3: GameSessionCoordinator,
        p4: GameSessionCoordinator,
    ) {
        p2.refresh()
        p3.refresh()
        p4.refresh()
    }

    private fun assertReplicasConverged(
        authoritative: grandlineduo.core.model.WorldState,
        p2: GameSessionCoordinator,
        p3: GameSessionCoordinator,
        p4: GameSessionCoordinator,
    ) {
        assertEquals(authoritative, p2.worldState())
        assertEquals(authoritative, p3.worldState())
        assertEquals(authoritative, p4.worldState())
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
