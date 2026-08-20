package grandlineduo.game.character

import grandlineduo.core.model.PlayerState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ProgressionEngineTest {
    fun register() {
        test("evolution points are awarded explicitly") {
            val updated = ProgressionEngine.awardEvolutionPoints(baseProfile(), 4)
            assertEquals(4, updated.evolutionPoints)
        }

        test("trained attribute increase spends three PEV and consumes training") {
            val prepared = ProgressionEngine.markAttributeTraining(
                ProgressionEngine.awardEvolutionPoints(baseProfile(), 5),
                Attribute.VON,
            )
            val result = ProgressionEngine.increaseAttribute(prepared, Attribute.VON)
            assertTrue(result is ProgressionResult.Success)
            val profile = (result as ProgressionResult.Success).profile
            assertEquals(2, profile.attributes.getValue(Attribute.VON))
            assertEquals(2, profile.evolutionPoints)
            assertTrue("attribute:VON" !in profile.trainingMarks)
        }

        test("attribute increase without matching training is rejected without mutation") {
            val original = ProgressionEngine.awardEvolutionPoints(baseProfile(), 5)
            val result = ProgressionEngine.increaseAttribute(original, Attribute.VON)
            assertEquals(ProgressionError.MISSING_TRAINING, (result as ProgressionResult.Rejected).error)
            assertEquals(original, result.profile)
        }

        test("trained skill increase spends two PEV and consumes training") {
            val prepared = ProgressionEngine.markSkillTraining(
                ProgressionEngine.awardEvolutionPoints(baseProfile(), 3),
                Skill.NAVIGATION,
            )
            val result = ProgressionEngine.increaseSkill(prepared, Skill.NAVIGATION)
            val profile = (result as ProgressionResult.Success).profile
            assertEquals(3, profile.skills.getValue(Skill.NAVIGATION))
            assertEquals(1, profile.evolutionPoints)
            assertTrue("skill:NAVIGATION" !in profile.trainingMarks)
        }

        test("attribute and skill hard caps are enforced") {
            val cappedAttribute = baseProfile().copy(
                attributes = baseProfile().attributes + (Attribute.DES to 5),
                evolutionPoints = 10,
                trainingMarks = setOf("attribute:DES"),
            )
            val cappedSkill = baseProfile().copy(
                skills = baseProfile().skills + (Skill.NAVIGATION to 5),
                evolutionPoints = 10,
                trainingMarks = setOf("skill:NAVIGATION"),
            )
            assertEquals(
                ProgressionError.ATTRIBUTE_CAP,
                (ProgressionEngine.increaseAttribute(cappedAttribute, Attribute.DES) as ProgressionResult.Rejected).error,
            )
            assertEquals(
                ProgressionError.SKILL_CAP,
                (ProgressionEngine.increaseSkill(cappedSkill, Skill.NAVIGATION) as ProgressionResult.Rejected).error,
            )
        }

        test("syncing a progressed profile updates maxima while preserving hp and energy deficits") {
            val old = baseProfile()
            val player = PlayerState(
                playerId = "p1",
                name = old.name,
                hp = old.maxHp - 5,
                maxHp = old.maxHp,
                bounty = 0,
                energy = old.maxEnergy - 4,
                maxEnergy = old.maxEnergy,
                profile = old,
            )
            val progressed = old.copy(
                attributes = old.attributes + (Attribute.CON to 3) + (Attribute.VON to 2),
            )

            val synced = CharacterStateSync.applyProfile(player, progressed)

            assertEquals(35, synced.maxHp)
            assertEquals(30, synced.hp)
            assertEquals(22, synced.maxEnergy)
            assertEquals(18, synced.energy)
            assertEquals(progressed, synced.profile)
        }

        test("insufficient PEV rejects trained progression deterministically") {
            val prepared = ProgressionEngine.markAttributeTraining(baseProfile(), Attribute.FOR)
            val result = ProgressionEngine.increaseAttribute(prepared, Attribute.FOR)
            assertEquals(ProgressionError.INSUFFICIENT_PEV, (result as ProgressionResult.Rejected).error)
            assertEquals(prepared, result.profile)
        }
    }

    internal fun baseProfile(): CharacterProfile =
        (CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success).profile
}
