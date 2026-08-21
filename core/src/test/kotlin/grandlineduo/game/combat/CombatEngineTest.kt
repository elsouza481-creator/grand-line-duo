package grandlineduo.game.combat

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object CombatEngineTest {
    fun register() {
        test("each player locks exactly one action before a round can resolve") {
            val engine = CombatEngine(seed = 10)
            var state = sampleState()
            state = engine.lockAction(state, CombatAction("p1", CombatActionType.ATTACK))
            assertEquals(null, engine.resolveIfReady(state))

            var failed = false
            try { engine.lockAction(state, CombatAction("p1", CombatActionType.DEFEND)) }
            catch (_: CombatRuleException) { failed = true }
            assertEquals(true, failed)
        }

        test("same combat state seed and actions resolve deterministically") {
            fun resolve(): CombatRoundResult {
                val engine = CombatEngine(seed = 991)
                var state = sampleState()
                state = engine.lockAction(state, CombatAction("p1", CombatActionType.ATTACK))
                state = engine.lockAction(state, CombatAction("p2", CombatActionType.DEFEND))
                return engine.resolveIfReady(state)!!
            }
            assertEquals(resolve(), resolve())
        }

        test("setup plus finisher triggers a cooperative combo") {
            val engine = CombatEngine(seed = 55)
            var state = sampleState(enemyHp = 100)
            state = engine.lockAction(state, CombatAction("p1", CombatActionType.SETUP))
            state = engine.lockAction(state, CombatAction("p2", CombatActionType.FINISHER))
            val result = engine.resolveIfReady(state)!!

            assertEquals(true, result.coopCombo)
            assertTrue(result.enemyDamage >= 45)
            assertTrue(result.log.any { "CO-OP" in it })
        }

        test("any two four player party members can trigger the cooperative combo") {
            val engine = CombatEngine(seed = 56)
            var state = sampleState(enemyHp = 200).copy(
                players = sampleState().players + mapOf(
                    "p3" to Combatant("p3", "Rika", 58, 58),
                    "p4" to Combatant("p4", "Bram", 62, 62),
                ),
            )
            state = engine.lockAction(state, CombatAction("p1", CombatActionType.DEFEND))
            state = engine.lockAction(state, CombatAction("p2", CombatActionType.ATTACK))
            state = engine.lockAction(state, CombatAction("p3", CombatActionType.SETUP))
            state = engine.lockAction(state, CombatAction("p4", CombatActionType.FINISHER))
            val result = engine.resolveIfReady(state)!!

            assertEquals(true, result.coopCombo)
            assertTrue(result.log.any { "CO-OP" in it })
        }

        test("defending reduces damage from enemy telegraph") {
            val attackingEngine = CombatEngine(seed = 77)
            var attackState = sampleState(telegraphTarget = "p1")
            attackState = attackingEngine.lockAction(attackState, CombatAction("p1", CombatActionType.ATTACK))
            attackState = attackingEngine.lockAction(attackState, CombatAction("p2", CombatActionType.ATTACK))
            val attacking = attackingEngine.resolveIfReady(attackState)!!

            val defendingEngine = CombatEngine(seed = 77)
            var defendState = sampleState(telegraphTarget = "p1")
            defendState = defendingEngine.lockAction(defendState, CombatAction("p1", CombatActionType.DEFEND))
            defendState = defendingEngine.lockAction(defendState, CombatAction("p2", CombatActionType.ATTACK))
            val defending = defendingEngine.resolveIfReady(defendState)!!

            assertTrue(defending.playerDamage.getValue("p1") < attacking.playerDamage.getValue("p1"))
        }

        test("dodge avoids a heavy strike telegraphed at that player") {
            val engine = CombatEngine(seed = 20)
            var state = sampleState(telegraphTarget = "p2")
            state = engine.lockAction(state, CombatAction("p1", CombatActionType.ATTACK))
            state = engine.lockAction(state, CombatAction("p2", CombatActionType.DODGE))
            val result = engine.resolveIfReady(state)!!
            assertEquals(0, result.playerDamage.getValue("p2"))
        }

        test("combat modifiers increase outgoing damage without changing base randomness") {
            fun resolve(engine: CombatEngine): CombatRoundResult {
                var state = sampleState(enemyHp = 200)
                state = engine.lockAction(state, CombatAction("p1", CombatActionType.ATTACK))
                state = engine.lockAction(state, CombatAction("p2", CombatActionType.DEFEND))
                return engine.resolveIfReady(state)!!
            }
            val base = resolve(CombatEngine(seed = 333))
            val boosted = resolve(CombatEngine(seed = 333, modifiers = mapOf("p1" to CombatModifiers(attackBonus = 7))))
            assertEquals(base.enemyDamage + 7, boosted.enemyDamage)
        }

        test("combat damage reduction lowers incoming damage") {
            fun resolve(engine: CombatEngine): CombatRoundResult {
                var state = sampleState(enemyHp = 200, telegraphTarget = "p1")
                state = engine.lockAction(state, CombatAction("p1", CombatActionType.ATTACK))
                state = engine.lockAction(state, CombatAction("p2", CombatActionType.DEFEND))
                return engine.resolveIfReady(state)!!
            }
            val base = resolve(CombatEngine(seed = 444))
            val armored = resolve(CombatEngine(seed = 444, modifiers = mapOf("p1" to CombatModifiers(damageReduction = 5))))
            assertEquals((base.playerDamage.getValue("p1") - 5).coerceAtLeast(0), armored.playerDamage.getValue("p1"))
        }

        test("reducing enemy HP to zero ends combat in victory") {
            val engine = CombatEngine(seed = 1)
            var state = sampleState(enemyHp = 20)
            state = engine.lockAction(state, CombatAction("p1", CombatActionType.SETUP))
            state = engine.lockAction(state, CombatAction("p2", CombatActionType.FINISHER))
            val result = engine.resolveIfReady(state)!!
            assertEquals(CombatStatus.VICTORY, result.state.status)
            assertEquals(0, result.state.enemy.hp)
        }
    }

    private fun sampleState(
        enemyHp: Int = 120,
        telegraphTarget: String = "p1",
    ) = CombatState(
        round = 1,
        players = mapOf(
            "p1" to Combatant("p1", "Kairo", 60, 60),
            "p2" to Combatant("p2", "Namiya", 55, 55),
        ),
        enemy = EnemyCombatant("veyron", "Capitão Veyron", enemyHp, 120, 18),
        telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, telegraphTarget),
    )
}
