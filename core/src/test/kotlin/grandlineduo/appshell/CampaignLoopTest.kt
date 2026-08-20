package grandlineduo.appshell

import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.InventoryEngine
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.ship.VoyageAction
import grandlineduo.game.world.ExplorationDirection
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object CampaignLoopTest {
    fun register() {
        test("solo completes Stormglass sails through voyage and starts first main arc") {
            val root = Files.createTempDirectory("gld-campaign-loop")
            GameSessionCoordinator(root).use { session ->
                session.startSolo("campaign-loop")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Arlen"))

                session.submitScenarioChoice("help_dockworker")
                session.submitScenarioChoice("question_dockworker")
                session.submitScenarioChoice("set_ambush")
                var guard = 0
                while (StormglassPersistenceAdapter.decode(session.worldState()).combat != null && guard++ < 20) {
                    session.submitCombatAction(CombatActionType.SETUP)
                }
                assertTrue(guard < 20, "Stormglass boss should be defeatable")
                assertEquals(ScenarioStage.RETURN_TO_SHIP, StormglassPersistenceAdapter.decode(session.worldState()).scenario.stage)
                session.submitScenarioChoice("return_to_ship")
                assertEquals(ScenarioStage.COMPLETE, StormglassPersistenceAdapter.decode(session.worldState()).scenario.stage)
                assertTrue(session.worldState().players.getValue("p1").profile!!.evolutionPoints >= 2)
                assertTrue(session.worldState().players.getValue("p2").profile!!.evolutionPoints >= 2)

                session.advanceCampaign()
                assertTrue(session.worldState().activeVoyage != null)
                session.submitVoyageAction(VoyageAction.HELM)
                val after = session.worldState()
                assertEquals(null, after.activeVoyage)
                assertTrue(after.activeArc != null)
                assertEquals(ArcPhase.ARRIVAL, after.activeArc!!.phase)
                assertEquals("emberwake", after.islandId)
            }
        }

        test("solo campaign remains playable beyond the old five island ending") {
            val root = Files.createTempDirectory("gld-endless-campaign")
            GameSessionCoordinator(root).use { session ->
                session.startSolo("campaign-endless-e2e")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Arlen"))
                var steps = 0
                val visited = linkedSetOf<String>()

                while ((session.worldState().worldFlags["world.voyages"]?.toIntOrNull() ?: 0) < 8 && steps++ < 1000) {
                    val world = session.worldState()
                    visited += world.islandId
                    val view = GamePresenter.present(world, "p1")
                    try {
                        when (view.screen) {
                            GameScreen.STORY -> session.submitScenarioChoice(view.actions.first().id)
                            GameScreen.ARC -> session.submitArcChoice(view.actions.first().id)
                            GameScreen.COMBAT -> {
                                val combat = world.activeCombat ?: StormglassPersistenceAdapter.decode(world).combat!!
                                val action = if (combat.telegraph.targetPlayerId == "p1" && combat.telegraph.type == EnemyAttackType.HEAVY_STRIKE) {
                                    CombatActionType.DODGE
                                } else CombatActionType.SETUP
                                session.submitCombatAction(action)
                            }
                            GameScreen.VOYAGE -> session.submitVoyageAction(VoyageAction.HELM)
                            GameScreen.HUB -> {
                                var current = session.worldState()
                                var inventory = InventoryEngine.read(current, "p1")
                                while (current.players.getValue("p1").hp < current.players.getValue("p1").maxHp && (inventory.items["bandage"] ?: 0) > 0) {
                                    session.submitInventoryAction("USE", "bandage")
                                    current = session.worldState()
                                    inventory = InventoryEngine.read(current, "p1")
                                }
                                if (current.players.getValue("p1").hp < current.players.getValue("p1").maxHp && current.partyBerries >= 250L) {
                                    runCatching { session.submitWorldAction("SHOP_BUY", "bandage", 2) }
                                    repeat(2) { runCatching { session.submitInventoryAction("USE", "bandage") } }
                                }
                                moveP1ToDock(session)
                                val sail = GamePresenter.present(session.worldState(), "p1").actions.first { it.kind == "CAMPAIGN" }
                                session.advanceCampaign(sail.id)
                            }
                            GameScreen.WAITING_FOR_PARTNER -> session.refresh()
                            GameScreen.END -> error("Endless world must not enter a fixed epilogue")
                            GameScreen.GAME_OVER -> error("Campaign became unwinnable at step $steps")
                            GameScreen.CHARACTER_CREATION -> error("Character unexpectedly missing")
                        }
                    } catch (failure: Throwable) {
                        val failed = session.worldState()
                        val arc = failed.activeArc
                        val activeCombat = failed.activeCombat
                        val legacyCombat = runCatching { StormglassPersistenceAdapter.decode(failed).combat }.getOrNull()
                        error(
                            "step=$steps screen=${view.screen} island=${failed.islandId} voyages=${failed.worldFlags["world.voyages"]} " +
                                "arcPhase=${arc?.phase} arcActed=${arc?.actedThisPhase} activeCombat=${activeCombat?.status} " +
                                "activeLocked=${activeCombat?.lockedActions?.keys} legacyCombat=${legacyCombat?.status} " +
                                "cause=${failure.message}"
                        )
                    }
                }

                val final = session.worldState()
                assertTrue(steps < 1000, "Endless campaign must continue progressing")
                assertTrue((final.worldFlags["world.voyages"]?.toIntOrNull() ?: 0) >= 8)
                assertTrue(final.worldFlags["campaign.complete"] != "true")
                assertTrue(visited.size >= 6)
            }
        }
    }

    private fun moveP1ToDock(session: GameSessionCoordinator) {
        var guard = 0
        while (ExplorationEngine.interactionAt(session.worldState(), "p1") != ExplorationInteraction.DOCK && guard++ < 40) {
            val world = session.worldState()
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val current = ExplorationEngine.position(world, "p1")
            val dock = map.interactions.entries.first { it.value == ExplorationInteraction.DOCK }.key
            val direction = when {
                current.x < dock.x -> ExplorationDirection.EAST
                current.x > dock.x -> ExplorationDirection.WEST
                current.y < dock.y -> ExplorationDirection.SOUTH
                else -> ExplorationDirection.NORTH
            }
            session.submitWorldAction("EXPLORE_MOVE", direction.name, 999)
        }
        assertTrue(guard < 40, "P1 must be able to walk to the physical dock")
    }
}
