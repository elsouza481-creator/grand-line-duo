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

        test("four player training arena exposes selectable rivals and spectators stay outside the duel") {
            var world = fourPlayerWorld("duel-present-four")
            val training = trainingPosition(world)
            listOf("p1", "p2", "p3", "p4").forEach { playerId ->
                world = ExplorationEngine.place(world, playerId, training)
            }

            val p3Offer = GamePresenter.present(world, "p3")
            val offeredRivals = p3Offer.actions
                .filter { it.kind == "DUEL_CHALLENGE" }
                .map { it.id }
                .toSet()
            assertEquals(setOf("p1", "p2", "p4"), offeredRivals)

            world = TrainingDuelEngine.challenge(world, "p3", "p4")
            val challenger = GamePresenter.present(world, "p3")
            val opponent = GamePresenter.present(world, "p4")
            val observer = GamePresenter.present(world, "p1")
            assertTrue("Dara" in challenger.body)
            assertTrue("Cato" in opponent.body)
            assertTrue(challenger.actions.any { it.kind == "DUEL_CANCEL" })
            assertTrue(opponent.actions.any { it.kind == "DUEL_ACCEPT" })
            assertTrue(observer.actions.none { it.kind.startsWith("DUEL_") })
            assertTrue("P3" in observer.body.uppercase() && "P4" in observer.body.uppercase())

            world = TrainingDuelEngine.accept(world, "p4")
            val activeP3 = GamePresenter.present(world, "p3")
            val activeP4 = GamePresenter.present(world, "p4")
            val activeObserver = GamePresenter.present(world, "p2")
            assertTrue("28" in activeP3.body && "22" in activeP3.body)
            assertTrue("22" in activeP4.body && "28" in activeP4.body)
            assertEquals(
                setOf("ATTACK", "DEFEND", "DODGE"),
                activeP3.actions.filter { it.kind == "DUEL_ACTION" }.map { it.id }.toSet(),
            )
            assertEquals(
                setOf("ATTACK", "DEFEND", "DODGE"),
                activeP4.actions.filter { it.kind == "DUEL_ACTION" }.map { it.id }.toSet(),
            )
            assertTrue(activeObserver.actions.none { it.kind.startsWith("DUEL_") })
        }

        test("training arena exposes the local persistent duel record only while physically present") {
            var world = fourPlayerWorld("duel-present-record").copy(
                worldFlags = fourPlayerWorld("duel-present-record").worldFlags + mapOf(
                    "duel.record.p3.wins" to "3",
                    "duel.record.p3.losses" to "2",
                    "duel.record.p3.draws" to "1",
                    "duel.record.p3.forfeits" to "1",
                )
            )

            val away = GamePresenter.present(world, "p3")
            assertEquals(null, away.exploration?.arenaRecord)

            world = ExplorationEngine.place(world, "p3", trainingPosition(world))
            val atArena = GamePresenter.present(world, "p3")
            assertEquals("Arena • 3V • 2D • 1E • 1 desistência", atArena.exploration?.arenaRecord)
        }

        test("challenged player can accept or decline while challenger can cancel in the arena") {
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
            assertTrue(challenger.actions.any { it.kind == "DUEL_CANCEL" })
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
        val p1Profile = profile("Kairo")
        val p2Profile = profile("Namiya")
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

    private fun fourPlayerWorld(id: String): WorldState {
        val profiles = mapOf(
            "p1" to profile("Kairo"),
            "p2" to profile("Namiya"),
            "p3" to profile("Cato"),
            "p4" to profile("Dara"),
        )
        return WorldState(
            campaignId = id,
            islandId = "stormglass-cay",
            players = mapOf(
                "p1" to PlayerState("p1", "Kairo", 30, 30, 0, profile = profiles.getValue("p1")),
                "p2" to PlayerState("p2", "Namiya", 24, 24, 0, profile = profiles.getValue("p2")),
                "p3" to PlayerState("p3", "Cato", 28, 28, 0, profile = profiles.getValue("p3")),
                "p4" to PlayerState("p4", "Dara", 22, 22, 0, profile = profiles.getValue("p4")),
            ),
            worldFlags = mapOf("sg.stage" to "COMPLETE"),
        )
    }

    private fun profile(name: String) =
        (CharacterCreation.create(GameSessionCoordinatorTest.validDraft(name)) as CharacterCreationResult.Success).profile
}
