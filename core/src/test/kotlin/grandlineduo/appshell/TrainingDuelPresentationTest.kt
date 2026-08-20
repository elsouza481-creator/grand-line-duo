package grandlineduo.appshell

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.pvp.TrainingDuelAction
import grandlineduo.game.pvp.TrainingDuelEngine
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object TrainingDuelPresentationTest {
    fun register() {
        test("training area offers a physical duel only when both players are present") {
            var world = world("duel-present-offer")
            val training = trainingPosition(world)
            world = ExplorationEngine.place(world, "p1", training)

            val alone = GamePresenter.present(world, "p1")
            assertTrue(alone.actions.none { it.kind == "DUEL_CHALLENGE" })

            world = ExplorationEngine.place(world, "p2", training)
            val together = GamePresenter.present(world, "p1")
            assertTrue(together.actions.any { it.kind == "DUEL_CHALLENGE" })
            assertTrue(together.actions.any { it.kind == "EXPLORE_MOVE" })
        }

        test("challenged player can accept or decline while challenger waits in the arena") {
            var world = world("duel-present-challenge")
            val training = trainingPosition(world)
            world = ExplorationEngine.place(world, "p1", training)
            world = ExplorationEngine.place(world, "p2", training)
            world = TrainingDuelEngine.challenge(world, "p1")

            val challenger = GamePresenter.present(world, "p1")
            val opponent = GamePresenter.present(world, "p2")
            assertTrue("desafio" in challenger.body.lowercase())
            assertTrue(challenger.actions.none { it.kind == "EXPLORE_MOVE" })
            assertTrue(challenger.actions.none { it.kind == "DUEL_ACCEPT" })
            assertTrue(opponent.actions.any { it.kind == "DUEL_ACCEPT" })
            assertTrue(opponent.actions.any { it.kind == "DUEL_DECLINE" })
            assertTrue(opponent.actions.none { it.kind == "EXPLORE_MOVE" })
        }

        test("active duel presents temporary hp simultaneous actions and waiting state") {
            var world = world("duel-present-active")
            val training = trainingPosition(world)
            world = ExplorationEngine.place(world, "p1", training)
            world = ExplorationEngine.place(world, "p2", training)
            world = TrainingDuelEngine.accept(TrainingDuelEngine.challenge(world, "p1"), "p2")

            val active = GamePresenter.present(world, "p1")
            assertTrue("duelo" in active.body.lowercase())
            assertTrue("30" in active.body)
            assertTrue("24" in active.body)
            assertEquals(setOf("ATTACK", "DEFEND", "DODGE"), active.actions.filter { it.kind == "DUEL_ACTION" }.map { it.id }.toSet())
            assertTrue(active.actions.any { it.kind == "DUEL_FORFEIT" })
            assertTrue(active.actions.none { it.kind == "EXPLORE_MOVE" || it.kind == "MENU" })

            world = TrainingDuelEngine.submitAction(world, "p1", TrainingDuelAction.ATTACK)
            val locked = GamePresenter.present(world, "p1")
            assertTrue("aguard" in locked.body.lowercase())
            assertTrue(locked.actions.none { it.kind == "DUEL_ACTION" })
            assertTrue(locked.actions.any { it.kind == "DUEL_FORFEIT" })
        }
    }

    private fun trainingPosition(world: WorldState) = ExplorationEngine
        .mapFor(world.campaignId, world.islandId)
        .interactions.entries.single { it.value == ExplorationInteraction.TRAINING }.key

    private fun world(id: String): WorldState {
        val p1Profile = (CharacterCreation.create(GameSessionCoordinatorTest.validDraft("Kairo")) as CharacterCreationResult.Success).profile
        val p2Profile = (CharacterCreation.create(GameSessionCoordinatorTest.validDraft("Namiya")) as CharacterCreationResult.Success).profile
        return WorldState(
            campaignId = id,
            islandId = "stormglass-cay",
            players = mapOf(
                "p1" to PlayerState("p1", p1Profile.name, 30, 30, 0, profile = p1Profile),
                "p2" to PlayerState("p2", p2Profile.name, 24, 24, 0, profile = p2Profile),
            ),
            worldFlags = mapOf("sg.stage" to "COMPLETE"),
        )
    }
}
