package grandlineduo.game.pvp

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object TrainingDuelEngineTest {
    fun register() {
        test("training duel challenge requires both human players at the physical training area") {
            var world = world("duel-physical")
            val training = trainingPosition(world)
            world = ExplorationEngine.place(world, "p1", training)

            var rejected = false
            try {
                TrainingDuelEngine.challenge(world, "p1")
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)

            world = ExplorationEngine.place(world, "p2", training)
            world = TrainingDuelEngine.challenge(world, "p1")
            val pending = requireNotNull(TrainingDuelEngine.state(world))
            assertEquals(TrainingDuelStatus.CHALLENGED, pending.status)
            assertEquals("p1", pending.challengerId)
            assertEquals("p2", pending.opponentId)

            world = TrainingDuelEngine.accept(world, "p2")
            val active = requireNotNull(TrainingDuelEngine.state(world))
            assertEquals(TrainingDuelStatus.ACTIVE, active.status)
            assertEquals(1, active.round)
            assertEquals(30, active.duelHp.getValue("p1"))
            assertEquals(24, active.duelHp.getValue("p2"))
            assertEquals(30, world.players.getValue("p1").hp)
            assertEquals(24, world.players.getValue("p2").hp)
        }

        test("training duel resolves locked actions simultaneously without touching persistent hp") {
            var world = activeDuel("duel-round")
            world = TrainingDuelEngine.submitAction(world, "p1", TrainingDuelAction.ATTACK)
            val halfLocked = requireNotNull(TrainingDuelEngine.state(world))
            assertEquals(mapOf("p1" to TrainingDuelAction.ATTACK), halfLocked.lockedActions)
            assertEquals(24, halfLocked.duelHp.getValue("p2"))

            world = TrainingDuelEngine.submitAction(world, "p2", TrainingDuelAction.DEFEND)
            val resolved = requireNotNull(TrainingDuelEngine.state(world))
            assertEquals(2, resolved.round)
            assertTrue(resolved.lockedActions.isEmpty())
            assertEquals(21, resolved.duelHp.getValue("p2"))
            assertEquals(30, resolved.duelHp.getValue("p1"))
            assertEquals(30, world.players.getValue("p1").hp)
            assertEquals(24, world.players.getValue("p2").hp)
        }

        test("training duel victory records winner and never kills the persistent character") {
            var world = world("duel-finish", p1Hp = 16, p2Hp = 8)
            val training = trainingPosition(world)
            world = ExplorationEngine.place(world, "p1", training)
            world = ExplorationEngine.place(world, "p2", training)
            world = TrainingDuelEngine.challenge(world, "p1")
            world = TrainingDuelEngine.accept(world, "p2")

            world = TrainingDuelEngine.submitAction(world, "p1", TrainingDuelAction.ATTACK)
            world = TrainingDuelEngine.submitAction(world, "p2", TrainingDuelAction.ATTACK)

            assertEquals(null, TrainingDuelEngine.state(world))
            assertEquals("p1", TrainingDuelEngine.lastWinner(world))
            assertEquals(16, world.players.getValue("p1").hp)
            assertEquals(8, world.players.getValue("p2").hp)
        }
    }

    private fun activeDuel(id: String): WorldState {
        var world = world(id)
        val training = trainingPosition(world)
        world = ExplorationEngine.place(world, "p1", training)
        world = ExplorationEngine.place(world, "p2", training)
        world = TrainingDuelEngine.challenge(world, "p1")
        return TrainingDuelEngine.accept(world, "p2")
    }

    private fun trainingPosition(world: WorldState) = ExplorationEngine
        .mapFor(world.campaignId, world.islandId)
        .interactions
        .entries
        .single { it.value == ExplorationInteraction.TRAINING }
        .key

    private fun world(id: String, p1Hp: Int = 30, p2Hp: Int = 24) = WorldState(
        campaignId = id,
        islandId = "stormglass-cay",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", p1Hp, p1Hp, 0),
            "p2" to PlayerState("p2", "Namiya", p2Hp, p2Hp, 0),
        ),
    )
}
