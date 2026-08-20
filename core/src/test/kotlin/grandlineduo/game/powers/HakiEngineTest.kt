package grandlineduo.game.powers

import grandlineduo.game.character.Attribute
import grandlineduo.game.character.CharacterProfile
import grandlineduo.game.character.ProgressionEngineTest
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object HakiEngineTest {
    fun register() {
        test("Kenbunshoku can awaken through focused training with perception aptitude") {
            val profile = baseProfile().copy(
                attributes = baseProfile().attributes + (Attribute.PER to 2),
            )
            val result = HakiEngine.attemptAwakening(
                profile,
                HakiState(),
                HakiType.KENBUNSHOKU,
                HakiTrigger.TRAINING,
                intensity = 2,
            )
            assertTrue(result is HakiAwakeningResult.Awakened)
            val state = (result as HakiAwakeningResult.Awakened).state
            assertEquals(1, state.disciplines.getValue(HakiType.KENBUNSHOKU).mastery)
        }

        test("Busoshoku can awaken under necessity with physical or will aptitude") {
            val result = HakiEngine.attemptAwakening(
                baseProfile(),
                HakiState(),
                HakiType.BUSOSHOKU,
                HakiTrigger.NECESSITY,
                intensity = 3,
            )
            assertTrue(result is HakiAwakeningResult.Awakened)
        }

        test("Haoshoku requires latent potential and an extreme narrative trigger") {
            val withoutPotential = HakiEngine.attemptAwakening(
                baseProfile(),
                HakiState(latentHaoshoku = false),
                HakiType.HAOSHOKU,
                HakiTrigger.TRAUMA,
                intensity = 5,
            )
            assertEquals(
                HakiAwakeningFailure.NO_HAOSHOKU_POTENTIAL,
                (withoutPotential as HakiAwakeningResult.Rejected).reason,
            )

            val trainingAttempt = HakiEngine.attemptAwakening(
                baseProfile(),
                HakiState(latentHaoshoku = true),
                HakiType.HAOSHOKU,
                HakiTrigger.TRAINING,
                intensity = 5,
            )
            assertEquals(
                HakiAwakeningFailure.TRIGGER_NOT_EXTREME,
                (trainingAttempt as HakiAwakeningResult.Rejected).reason,
            )

            val awakened = HakiEngine.attemptAwakening(
                baseProfile(),
                HakiState(latentHaoshoku = true),
                HakiType.HAOSHOKU,
                HakiTrigger.EXTREME_WILL,
                intensity = 5,
            )
            assertTrue(awakened is HakiAwakeningResult.Awakened)
        }

        test("same Haki discipline cannot awaken twice") {
            val initial = HakiState(
                disciplines = mapOf(HakiType.KENBUNSHOKU to HakiDiscipline(mastery = 1)),
            )
            val result = HakiEngine.attemptAwakening(
                baseProfile(), initial, HakiType.KENBUNSHOKU, HakiTrigger.TRAINING, 5,
            )
            assertEquals(
                HakiAwakeningFailure.ALREADY_AWAKENED,
                (result as HakiAwakeningResult.Rejected).reason,
            )
        }

        test("Haki mastery requires prior use plus training and consumes threshold uses") {
            var state = HakiState(
                disciplines = mapOf(HakiType.BUSOSHOKU to HakiDiscipline(mastery = 1)),
            )
            repeat(2) { state = HakiEngine.recordUse(state, HakiType.BUSOSHOKU) }
            val tooSoon = HakiEngine.trainMastery(state, HakiType.BUSOSHOKU)
            assertEquals(
                HakiMasteryFailure.INSUFFICIENT_USE,
                (tooSoon as HakiMasteryResult.Rejected).reason,
            )

            state = HakiEngine.recordUse(state, HakiType.BUSOSHOKU)
            val trained = HakiEngine.trainMastery(state, HakiType.BUSOSHOKU)
            val next = (trained as HakiMasteryResult.Advanced).state
            assertEquals(2, next.disciplines.getValue(HakiType.BUSOSHOKU).mastery)
            assertEquals(0, next.disciplines.getValue(HakiType.BUSOSHOKU).useCount)
        }

        test("Haki mastery is capped at six") {
            val state = HakiState(
                disciplines = mapOf(HakiType.KENBUNSHOKU to HakiDiscipline(mastery = 6, useCount = 99)),
            )
            val result = HakiEngine.trainMastery(state, HakiType.KENBUNSHOKU)
            assertEquals(
                HakiMasteryFailure.MASTERY_CAP,
                (result as HakiMasteryResult.Rejected).reason,
            )
        }
    }

    private fun baseProfile(): CharacterProfile = ProgressionEngineTest.baseProfile()
}
