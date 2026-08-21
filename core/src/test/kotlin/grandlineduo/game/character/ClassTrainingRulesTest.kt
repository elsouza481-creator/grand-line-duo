package grandlineduo.game.character

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ClassTrainingRulesTest {
    fun register() {
        test("class training rules apply fixed mastery gain and energy cost atomically") {
            val profile = createdProfile(ClassPath.NAVIGATOR)
            val initial = world(profile, energy = 12)

            val trained = ClassTrainingRules.train(initial, "p1", ClassPath.GUNNER)
            val player = trained.players.getValue("p1")
            val mastery = player.profile!!.classMastery!!

            assertEquals(5, ClassTrainingRules.ENERGY_COST)
            assertEquals(25L, ClassTrainingRules.EXPERIENCE_GAIN)
            assertEquals(7, player.energy)
            assertEquals(25L, mastery.experienceOf(ClassPath.GUNNER))
            assertEquals(ClassPath.NAVIGATOR, mastery.primaryClass)
        }

        test("class training rules reject insufficient energy without changing the supplied world") {
            val profile = createdProfile(ClassPath.NAVIGATOR)
            val initial = world(profile, energy = 4)
            var rejected = false

            try {
                ClassTrainingRules.train(initial, "p1", ClassPath.NAVIGATOR)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }

            assertTrue(rejected, "Shared class training rules must enforce their energy cost")
            assertEquals(4, initial.players.getValue("p1").energy)
            assertEquals(0L, initial.players.getValue("p1").profile!!.classMastery!!.experienceOf(ClassPath.NAVIGATOR))
        }
    }

    private fun createdProfile(path: ClassPath): CharacterProfile =
        (CharacterCreation.create(CharacterCreationTest.validDraft().copy(classPath = path)) as CharacterCreationResult.Success).profile

    private fun world(profile: CharacterProfile, energy: Int) = WorldState(
        campaignId = "class-training-rules",
        islandId = "stormglass-cay",
        players = mapOf(
            "p1" to PlayerState(
                playerId = "p1",
                name = profile.name,
                hp = profile.maxHp,
                maxHp = profile.maxHp,
                bounty = 0,
                energy = energy,
                maxEnergy = profile.maxEnergy,
                profile = profile,
            ),
            "p2" to PlayerState("p2", "Companion", 20, 20, 0),
        ),
    )
}
