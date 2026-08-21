package grandlineduo.appshell

import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.InventoryEngine
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.ship.VoyageAction
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

        test("solo campaign can reach the final epilogue through public gameplay APIs") {
            val root = Files.createTempDirectory("gld-full-campaign")
            GameSessionCoordinator(root).use { session ->
                session.startSolo("campaign-complete-e2e")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Arlen"))
                var steps = 0
                while (session.worldState().worldFlags["campaign.complete"] != "true" && steps++ < 700) {
                    val world = session.worldState()
                    val view = GamePresenter.present(world, "p1")
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
                            session.advanceCampaign()
                        }
                        GameScreen.QUESTS -> error("Quest overlay is not part of the automatic campaign loop")
                        GameScreen.WAITING_FOR_PARTNER -> session.refresh()
                        GameScreen.END -> break
                        GameScreen.GAME_OVER -> error("Campaign became unwinnable at step $steps")
                        GameScreen.CHARACTER_CREATION -> error("Character unexpectedly missing")
                    }
                }
                val final = session.worldState()
                assertTrue(steps < 700, "Campaign must not loop forever")
                assertEquals("true", final.worldFlags["campaign.complete"])
                assertTrue(!final.worldFlags["campaign.epilogue"].isNullOrBlank())
                assertTrue((final.worldFlags.keys.count { it.startsWith("reward.arc.") }) >= 5)
            }
        }
    }
}
