package grandlineduo.game.character

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ClassMasteryEngineTest {
    fun register() {
        test("class mastery starts at level zero for the chosen primary class") {
            val state = ClassMasteryEngine.start(ClassPath.SWORDSMAN)

            assertEquals(ClassPath.SWORDSMAN, state.primaryClass)
            assertEquals(0, state.levelOf(ClassPath.SWORDSMAN))
            assertEquals(0L, state.experienceOf(ClassPath.SWORDSMAN))
        }

        test("training grants mastery experience and levels up at the first threshold") {
            val state = ClassMasteryEngine.start(ClassPath.BRAWLER)
            val trained = ClassMasteryEngine.train(state, ClassPath.BRAWLER, 100)

            assertEquals(1, trained.levelOf(ClassPath.BRAWLER))
            assertEquals(0L, trained.experienceOf(ClassPath.BRAWLER))
        }

        test("mastery thresholds grow with level and preserve overflow experience") {
            val state = ClassMasteryEngine.start(ClassPath.NAVIGATOR)
            val levelOne = ClassMasteryEngine.train(state, ClassPath.NAVIGATOR, 100)
            val trained = ClassMasteryEngine.train(levelOne, ClassPath.NAVIGATOR, 140)

            assertEquals(2, trained.levelOf(ClassPath.NAVIGATOR))
            assertEquals(15L, trained.experienceOf(ClassPath.NAVIGATOR))
            assertEquals(150L, ClassMasteryEngine.experienceRequiredForLevel(2))
        }

        test("one training session can cross multiple mastery levels deterministically") {
            val state = ClassMasteryEngine.start(ClassPath.GUNNER)
            val trained = ClassMasteryEngine.train(state, ClassPath.GUNNER, 400)

            assertEquals(3, trained.levelOf(ClassPath.GUNNER))
            assertEquals(25L, trained.experienceOf(ClassPath.GUNNER))
        }

        test("secondary classes can be trained without replacing the primary class") {
            val state = ClassMasteryEngine.start(ClassPath.DOCTOR)
            val trained = ClassMasteryEngine.train(state, ClassPath.SHIPWRIGHT, 100)

            assertEquals(ClassPath.DOCTOR, trained.primaryClass)
            assertEquals(1, trained.levelOf(ClassPath.SHIPWRIGHT))
            assertEquals(0, trained.levelOf(ClassPath.DOCTOR))
        }

        test("class mastery has no gameplay level cap") {
            val state = ClassMasteryState(
                primaryClass = ClassPath.CAPTAIN,
                levels = mapOf(ClassPath.CAPTAIN to 1000),
                experience = mapOf(ClassPath.CAPTAIN to 0L),
            )
            val trained = ClassMasteryEngine.train(
                state,
                ClassPath.CAPTAIN,
                ClassMasteryEngine.experienceRequiredForLevel(1000),
            )

            assertEquals(1001, trained.levelOf(ClassPath.CAPTAIN))
        }

        test("mastery milestones unlock deterministic class perks") {
            val perksAt25 = ClassMasteryEngine.unlockedPerks(ClassPath.ROGUE, 25)

            assertTrue(ClassPerk.CLASS_INITIATE in perksAt25)
            assertTrue(ClassPerk.SPECIALIST in perksAt25)
            assertTrue(ClassPerk.VETERAN in perksAt25)
            assertTrue(ClassPerk.MASTER !in perksAt25)
        }
    }
}
