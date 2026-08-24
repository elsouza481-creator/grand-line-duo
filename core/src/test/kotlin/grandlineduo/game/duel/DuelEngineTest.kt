package grandlineduo.game.duel

import grandlineduo.game.combat.CombatAction
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatModifiers
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object DuelEngineTest {
    fun register() {
        test("duel round result is deterministic regardless of locked action map order") {
            val base = sampleState()
            val actionsA = mapOf(
                "p1" to CombatAction("p1", CombatActionType.ATTACK),
                "p2" to CombatAction("p2", CombatActionType.DEFEND),
            )
            val actionsB = linkedMapOf(
                "p2" to CombatAction("p2", CombatActionType.DEFEND),
                "p1" to CombatAction("p1", CombatActionType.ATTACK),
            )

            val a = DuelEngine(99L).resolveIfReady(base.copy(lockedActions = actionsA))!!
            val b = DuelEngine(99L).resolveIfReady(base.copy(lockedActions = actionsB))!!

            assertEquals(a, b)
        }

        test("each duel fighter can lock only one action per round") {
            val engine = DuelEngine(11L)
            val once = engine.lockAction(sampleState(), CombatAction("p1", CombatActionType.ATTACK))
            var rejected = false
            try {
                engine.lockAction(once, CombatAction("p1", CombatActionType.DEFEND))
            } catch (_: DuelRuleException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(null, engine.resolveIfReady(once))
        }

        test("defend reduces incoming duel damage") {
            val undefended = resolve(
                seed = 21L,
                p1Action = CombatActionType.ATTACK,
                p2Action = CombatActionType.ATTACK,
            )
            val defended = resolve(
                seed = 21L,
                p1Action = CombatActionType.ATTACK,
                p2Action = CombatActionType.DEFEND,
            )
            assertTrue(defended.damageTaken.getValue("p2") < undefended.damageTaken.getValue("p2"))
        }

        test("dodge can deterministically avoid a direct duel attack") {
            val result = (0L..5_000L).asSequence()
                .map { seed -> resolve(seed, CombatActionType.ATTACK, CombatActionType.DODGE) }
                .firstOrNull { it.damageTaken.getValue("p2") == 0 }
            assertTrue(result != null, "Expected at least one deterministic seed where dodge succeeds")
        }

        test("kenbunshoku has a higher deterministic avoidance threshold than dodge") {
            val seed = (0L..10_000L).firstOrNull { candidate ->
                val dodge = resolve(candidate, CombatActionType.ATTACK, CombatActionType.DODGE)
                val kenbun = resolve(candidate, CombatActionType.ATTACK, CombatActionType.HAKI_KENBUNSHOKU)
                dodge.damageTaken.getValue("p2") > 0 && kenbun.damageTaken.getValue("p2") == 0
            }
            assertTrue(seed != null, "Expected a seed where Kenbunshoku succeeds while ordinary dodge fails")
        }

        test("setup marks only its actor for the next duel round") {
            val result = resolve(
                seed = 31L,
                p1Action = CombatActionType.SETUP,
                p2Action = CombatActionType.DEFEND,
            )
            assertEquals(setOf("p1"), result.state.setupReady)
            assertEquals(2, result.state.round)
        }

        test("setup backed finisher adds exactly twelve damage before unguarded defense") {
            val raw = resolve(
                seed = 44L,
                p1Action = CombatActionType.FINISHER,
                p2Action = CombatActionType.ATTACK,
                state = sampleState(round = 2),
            )
            val prepared = resolve(
                seed = 44L,
                p1Action = CombatActionType.FINISHER,
                p2Action = CombatActionType.ATTACK,
                state = sampleState(round = 2, setupReady = setOf("p1")),
            )
            assertEquals(raw.damageTaken.getValue("p2") + 12, prepared.damageTaken.getValue("p2"))
        }

        test("attack bonus affects only its owner outgoing duel damage") {
            val base = resolve(55L, CombatActionType.ATTACK, CombatActionType.ATTACK)
            val boosted = resolve(
                55L,
                CombatActionType.ATTACK,
                CombatActionType.ATTACK,
                modifiers = mapOf("p1" to CombatModifiers(attackBonus = 7)),
            )
            assertEquals(base.damageTaken.getValue("p2") + 7, boosted.damageTaken.getValue("p2"))
            assertEquals(base.damageTaken.getValue("p1"), boosted.damageTaken.getValue("p1"))
        }

        test("damage reduction affects only its owner incoming duel damage") {
            val base = resolve(66L, CombatActionType.ATTACK, CombatActionType.ATTACK)
            val armored = resolve(
                66L,
                CombatActionType.ATTACK,
                CombatActionType.ATTACK,
                modifiers = mapOf("p2" to CombatModifiers(damageReduction = 5)),
            )
            assertEquals((base.damageTaken.getValue("p2") - 5).coerceAtLeast(0), armored.damageTaken.getValue("p2"))
            assertEquals(base.damageTaken.getValue("p1"), armored.damageTaken.getValue("p1"))
        }

        test("matching Haki and Devil Fruit bonuses affect their duel action categories") {
            fun assertPowerBonus(type: CombatActionType, modifiers: CombatModifiers, expected: Int) {
                val base = resolve(77L, type, CombatActionType.ATTACK)
                val boosted = resolve(77L, type, CombatActionType.ATTACK, modifiers = mapOf("p1" to modifiers))
                assertEquals(base.damageTaken.getValue("p2") + expected, boosted.damageTaken.getValue("p2"))
                assertEquals(base.damageTaken.getValue("p1"), boosted.damageTaken.getValue("p1"))
            }

            assertPowerBonus(CombatActionType.HAKI_BUSOSHOKU, CombatModifiers(busoshokuBonus = 4), 4)
            assertPowerBonus(CombatActionType.HAKI_HAOSHOKU, CombatModifiers(haoshokuBonus = 6), 6)
            assertPowerBonus(CombatActionType.DEVIL_FRUIT, CombatModifiers(devilFruitBonus = 5), 5)
        }

        test("single duel knockout is non lethal and records winner and loser") {
            val result = resolve(
                seed = 88L,
                p1Action = CombatActionType.FINISHER,
                p2Action = CombatActionType.ATTACK,
                state = sampleState(p2Hp = 5),
            )
            assertEquals(DuelPhase.FINISHED, result.state.phase)
            assertEquals(DuelFinishReason.KNOCKOUT, result.state.finishReason)
            assertEquals("p1", result.state.winnerId)
            assertEquals("p2", result.state.loserId)
            assertEquals(1, result.state.fighters.getValue("p2").hp)
            assertTrue(result.state.fighters.getValue("p1").hp >= 1)
        }

        test("simultaneous duel knockout ends in deterministic draw at one hp each") {
            val result = resolve(
                seed = 99L,
                p1Action = CombatActionType.ATTACK,
                p2Action = CombatActionType.ATTACK,
                state = sampleState(p1Hp = 1, p2Hp = 1),
            )
            assertEquals(DuelPhase.FINISHED, result.state.phase)
            assertEquals(DuelFinishReason.DOUBLE_KNOCKOUT, result.state.finishReason)
            assertEquals(null, result.state.winnerId)
            assertEquals(null, result.state.loserId)
            assertEquals(1, result.state.fighters.getValue("p1").hp)
            assertEquals(1, result.state.fighters.getValue("p2").hp)
        }

        test("duel resolves symmetrically without any PvE enemy or telegraph state") {
            val state = sampleState()
            assertEquals(setOf("p1", "p2"), state.fighters.keys)
            val result = resolve(123L, CombatActionType.ATTACK, CombatActionType.DEFEND, state)
            assertTrue(result.log.isNotEmpty())
            assertEquals(setOf("p1", "p2"), result.damageTaken.keys)
        }
    }

    private fun resolve(
        seed: Long,
        p1Action: CombatActionType,
        p2Action: CombatActionType,
        state: DuelState = sampleState(),
        modifiers: Map<String, CombatModifiers> = emptyMap(),
    ): DuelRoundResult {
        val engine = DuelEngine(seed, modifiers)
        var locked = engine.lockAction(state, CombatAction("p1", p1Action))
        locked = engine.lockAction(locked, CombatAction("p2", p2Action))
        return engine.resolveIfReady(locked) ?: error("Expected duel round to resolve")
    }

    private fun sampleState(
        round: Int = 1,
        p1Hp: Int = 60,
        p2Hp: Int = 60,
        setupReady: Set<String> = emptySet(),
    ) = DuelState(
        duelId = "duel-1",
        challengerId = "p1",
        challengedId = "p2",
        phase = DuelPhase.ACTIVE,
        round = round,
        fighters = mapOf(
            "p1" to DuelFighter("p1", "Kairo", p1Hp, 60),
            "p2" to DuelFighter("p2", "Namiya", p2Hp, 60),
        ),
        setupReady = setupReady,
    )
}
