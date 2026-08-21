package grandlineduo.game.pvp

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.game.world.GridPosition
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

        test("p3 can select p4 as a physical training duel opponent") {
            var world = fourPlayerWorld("duel-four")
            val training = trainingPosition(world)
            listOf("p1", "p2", "p3", "p4").forEach { playerId ->
                world = ExplorationEngine.place(world, playerId, training)
            }

            world = TrainingDuelEngine.challenge(world, "p3", "p4")
            val pending = requireNotNull(TrainingDuelEngine.state(world))
            assertEquals("p3", pending.challengerId)
            assertEquals("p4", pending.opponentId)

            world = TrainingDuelEngine.accept(world, "p4")
            val active = requireNotNull(TrainingDuelEngine.state(world))
            assertEquals(setOf("p3", "p4"), active.duelHp.keys)
            assertEquals(28, active.duelHp.getValue("p3"))
            assertEquals(22, active.duelHp.getValue("p4"))

            world = TrainingDuelEngine.submitAction(world, "p3", TrainingDuelAction.ATTACK)
            world = TrainingDuelEngine.submitAction(world, "p4", TrainingDuelAction.DEFEND)
            val resolved = requireNotNull(TrainingDuelEngine.state(world))
            assertEquals(2, resolved.round)
            assertEquals(setOf("p3", "p4"), resolved.duelHp.keys)
            assertEquals(28, world.players.getValue("p3").hp)
            assertEquals(22, world.players.getValue("p4").hp)
            assertEquals(30, world.players.getValue("p1").hp)
            assertEquals(24, world.players.getValue("p2").hp)
        }

        test("adjacent players can accept a nonlethal field spar without changing arena record") {
            var world = fourPlayerWorld("duel-field-spar").copy(partyBerries = 9_000)
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val first = map.spawn
            val second = GridPosition(first.x + 1, first.y)
            world = ExplorationEngine.place(world, "p3", first)
            world = ExplorationEngine.place(world, "p4", second)

            var distantRejected = false
            try {
                val distant = ExplorationEngine.place(world, "p4", GridPosition(first.x + 3, first.y))
                TrainingDuelEngine.challengeAdjacent(distant, "p3", "p4")
            } catch (_: IllegalArgumentException) {
                distantRejected = true
            }
            assertTrue(distantRejected)

            val berriesBefore = world.partyBerries
            val p3HpBefore = world.players.getValue("p3").hp
            val p4HpBefore = world.players.getValue("p4").hp
            world = TrainingDuelEngine.challengeAdjacent(world, "p3", "p4")
            val pending = requireNotNull(TrainingDuelEngine.state(world))
            assertEquals(TrainingDuelVenue.FIELD_SPARRING, pending.venue)

            world = TrainingDuelEngine.accept(world, "p4")
            world = TrainingDuelEngine.forfeit(world, "p4")

            assertEquals(null, TrainingDuelEngine.state(world))
            assertEquals("p3", TrainingDuelEngine.lastWinner(world))
            assertEquals(TrainingDuelRecord(), TrainingDuelEngine.record(world, "p3"))
            assertEquals(TrainingDuelRecord(), TrainingDuelEngine.record(world, "p4"))
            assertEquals(berriesBefore, world.partyBerries)
            assertEquals(p3HpBefore, world.players.getValue("p3").hp)
            assertEquals(p4HpBefore, world.players.getValue("p4").hp)
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

        test("arena record tracks wins losses and forfeits without gameplay rewards") {
            var world = fourPlayerWorld("duel-record-forfeit").copy(partyBerries = 7_500)
            val training = trainingPosition(world)
            world = ExplorationEngine.place(world, "p3", training)
            world = ExplorationEngine.place(world, "p4", training)
            val berriesBefore = world.partyBerries
            val p3HpBefore = world.players.getValue("p3").hp
            val p4HpBefore = world.players.getValue("p4").hp

            world = TrainingDuelEngine.accept(TrainingDuelEngine.challenge(world, "p3", "p4"), "p4")
            world = TrainingDuelEngine.forfeit(world, "p4")

            assertEquals(TrainingDuelRecord(wins = 1), TrainingDuelEngine.record(world, "p3"))
            assertEquals(TrainingDuelRecord(losses = 1, forfeits = 1), TrainingDuelEngine.record(world, "p4"))
            assertEquals(TrainingDuelRecord(), TrainingDuelEngine.record(world, "p1"))
            assertEquals(berriesBefore, world.partyBerries)
            assertEquals(p3HpBefore, world.players.getValue("p3").hp)
            assertEquals(p4HpBefore, world.players.getValue("p4").hp)
        }

        test("simultaneous knockout records one draw for both duelists") {
            var world = world("duel-record-draw", p1Hp = 8, p2Hp = 8)
            val training = trainingPosition(world)
            world = ExplorationEngine.place(world, "p1", training)
            world = ExplorationEngine.place(world, "p2", training)
            world = TrainingDuelEngine.accept(TrainingDuelEngine.challenge(world, "p1"), "p2")
            world = TrainingDuelEngine.submitAction(world, "p1", TrainingDuelAction.ATTACK)
            world = TrainingDuelEngine.submitAction(world, "p2", TrainingDuelAction.ATTACK)

            assertEquals("DRAW", TrainingDuelEngine.lastWinner(world))
            assertEquals(TrainingDuelRecord(draws = 1), TrainingDuelEngine.record(world, "p1"))
            assertEquals(TrainingDuelRecord(draws = 1), TrainingDuelEngine.record(world, "p2"))
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

    private fun fourPlayerWorld(id: String) = WorldState(
        campaignId = id,
        islandId = "stormglass-cay",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 30, 30, 0),
            "p2" to PlayerState("p2", "Namiya", 24, 24, 0),
            "p3" to PlayerState("p3", "Cato", 28, 28, 0),
            "p4" to PlayerState("p4", "Dara", 22, 22, 0),
        ),
    )
}
