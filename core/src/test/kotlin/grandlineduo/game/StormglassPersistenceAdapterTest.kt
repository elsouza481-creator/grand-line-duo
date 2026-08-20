package grandlineduo.game

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.combat.*
import grandlineduo.game.scenario.StormglassCayScenario
import grandlineduo.test.assertEquals
import grandlineduo.test.test
import java.nio.file.Files

object StormglassPersistenceAdapterTest {
    fun register() {
        test("scenario private knowledge and in-progress combat survive snapshot restart") {
            val scenario = StormglassCayScenario()
            var scenarioState = scenario.initialState()
            scenarioState = scenario.choose(scenarioState, "p1", "help_dockworker").state
            scenarioState = scenario.choose(scenarioState, "p2", "shadow_courier").state

            val combatEngine = CombatEngine(seed = 42)
            var combatState = CombatState(
                round = 2,
                players = mapOf(
                    "p1" to Combatant("p1", "Kairo", 51, 60),
                    "p2" to Combatant("p2", "Namiya", 55, 55),
                ),
                enemy = EnemyCombatant("veyron", "Capitão Veyron", 73, 120, 18),
                telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p2"),
            )
            combatState = combatEngine.lockAction(combatState, CombatAction("p1", CombatActionType.SETUP))

            val world = WorldState(
                campaignId = "vertical-save",
                players = mapOf(
                    "p1" to PlayerState("p1", "Kairo", 60, 60, 1_000_000),
                    "p2" to PlayerState("p2", "Namiya", 55, 55, 500_000),
                ),
            )
            val encoded = StormglassPersistenceAdapter.encode(world, scenarioState, combatState)
            val dir = Files.createTempDirectory("gld-vertical-save")
            SnapshotStore(dir).save(encoded)
            val reloadedWorld = SnapshotStore(dir).loadLatestValid()!!
            val restored = StormglassPersistenceAdapter.decode(reloadedWorld)

            assertEquals(scenarioState, restored.scenario)
            assertEquals(combatState, restored.combat)
        }
    }
}
