package grandlineduo.game.powers

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object DevilFruitEngineTest {
    fun register() {
        test("consuming an unidentified fruit grants one power but keeps identity hidden") {
            val definition = sampleFruit()
            val result = DevilFruitEngine.consume(null, definition, identified = false)
            assertTrue(result is DevilFruitConsumeResult.Consumed)
            val state = (result as DevilFruitConsumeResult.Consumed).state
            assertEquals(definition.id, state.fruitId)
            assertEquals(DevilFruitCategory.PARAMECIA, state.category)
            assertEquals(null, state.revealedName)
            assertEquals(0, state.mastery)
            assertEquals(false, DevilFruitEngine.canSwim(state))
            assertEquals(true, DevilFruitEngine.vulnerableToSeastone(state))
        }

        test("character cannot consume a second Devil Fruit") {
            val first = (DevilFruitEngine.consume(null, sampleFruit(), false) as DevilFruitConsumeResult.Consumed).state
            val second = DevilFruitEngine.consume(
                first,
                DevilFruitDefinition("mist-logia", "Mist-Mist Fruit", DevilFruitCategory.LOGIA),
                identified = true,
            )
            assertEquals(
                DevilFruitConsumeFailure.ALREADY_HAS_FRUIT,
                (second as DevilFruitConsumeResult.Rejected).reason,
            )
            assertEquals(first, second.state)
        }

        test("fruit identity can be revealed later without changing its power identity") {
            val definition = sampleFruit()
            val hidden = (DevilFruitEngine.consume(null, definition, false) as DevilFruitConsumeResult.Consumed).state
            val revealed = DevilFruitEngine.revealIdentity(hidden, definition)
            assertEquals(definition.displayName, revealed.revealedName)
            assertEquals(hidden.fruitId, revealed.fruitId)
            assertEquals(hidden.mastery, revealed.mastery)
        }

        test("fruit mastery requires use plus training and starts at zero") {
            var state = (DevilFruitEngine.consume(null, sampleFruit(), false) as DevilFruitConsumeResult.Consumed).state
            repeat(2) { state = DevilFruitEngine.recordUse(state) }
            val tooSoon = DevilFruitEngine.trainMastery(state)
            assertEquals(
                DevilFruitMasteryFailure.INSUFFICIENT_USE,
                (tooSoon as DevilFruitMasteryResult.Rejected).reason,
            )

            state = DevilFruitEngine.recordUse(state)
            val trained = DevilFruitEngine.trainMastery(state)
            val next = (trained as DevilFruitMasteryResult.Advanced).state
            assertEquals(1, next.mastery)
            assertEquals(0, next.useCount)
        }

        test("fruit mastery is capped at six") {
            val state = DevilFruitState(
                fruitId = "pulse-paramecia",
                category = DevilFruitCategory.PARAMECIA,
                revealedName = "Pulse-Pulse Fruit",
                mastery = 6,
                useCount = 99,
            )
            val result = DevilFruitEngine.trainMastery(state)
            assertEquals(
                DevilFruitMasteryFailure.MASTERY_CAP,
                (result as DevilFruitMasteryResult.Rejected).reason,
            )
        }
    }

    internal fun sampleFruit() = DevilFruitDefinition(
        id = "pulse-paramecia",
        displayName = "Pulse-Pulse Fruit",
        category = DevilFruitCategory.PARAMECIA,
    )
}
