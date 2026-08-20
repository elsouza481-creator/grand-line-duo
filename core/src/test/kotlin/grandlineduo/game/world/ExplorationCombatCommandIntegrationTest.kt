package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationCombatCommandIntegrationTest {
    fun register() {
        test("authoritative exploration movement onto a hostile tile starts free roam combat") {
            var initial = world("free-roam-command-start")
            val enemy = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).enemies.values.single()
            initial = ExplorationEngine.place(initial, "p1", GridPosition(enemy.position.x - 1, enemy.position.y))
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 71)

            val event = handler.handle(
                GameplayWireCommand.WorldAction("walk-into-hostile", "p1", "EXPLORE_MOVE", "EAST", 999),
                1,
            )

            assertEquals(enemy.position, ExplorationEngine.position(host.state, "p1"))
            assertTrue(ExplorationCombatEngine.isActive(host.state))
            assertEquals(enemy.id, host.state.activeCombat?.enemy?.id)
            assertEquals("EXPLORE_MOVE", event.payload["meta.worldAction"])
        }

        test("free roam basic combat resolves on host and victory reward is idempotent without an active arc") {
            var initial = world("free-roam-command-combat")
            val enemy = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).enemies.values.single()
            initial = ExplorationEngine.place(initial, "p1", enemy.position)
            initial = ExplorationCombatEngine.startIfEncountered(initial, "p1")
            initial = initial.copy(activeCombat = initial.activeCombat!!.copy(enemy = initial.activeCombat!!.enemy.copy(hp = 1)))
            val berriesBefore = initial.partyBerries
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 72)

            handler.handle(
                GameplayWireCommand.CombatAction("free-p1", "p1", CombatActionType.ATTACK.name),
                10,
            )
            val p2 = GameplayWireCommand.CombatAction("free-p2", "p2", CombatActionType.ATTACK.name)
            handler.handle(p2, 11)
            handler.handle(p2, 12)

            assertEquals(null, host.state.activeCombat)
            assertTrue(ExplorationCombatEngine.isDefeated(host.state, enemy.id))
            assertEquals(berriesBefore + enemy.rewardBerries, host.state.partyBerries)
        }
    }

    private fun world(id: String) = WorldState(
        campaignId = id,
        islandId = "stormglass-cay",
        partyBerries = 2_000,
        players = mapOf(
            "p1" to PlayerState("p1", "A", 40, 40, 0),
            "p2" to PlayerState("p2", "B", 40, 40, 0),
        ),
    )
}
