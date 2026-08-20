package grandlineduo.game.powers

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.combat.CombatActionType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object PowerTechniqueEngineTest {
    fun register() {
        test("available combat techniques reflect awakened Haki and Devil Fruit") {
            val world = poweredWorld()
            val techniques = PowerTechniqueEngine.available(world, "p1").map { it.id }.toSet()
            assertTrue("HAKI_BUSOSHOKU" in techniques)
            assertTrue("HAKI_KENBUNSHOKU" in techniques)
            assertTrue("DEVIL_FRUIT" in techniques)
            assertTrue("HAKI_HAOSHOKU" !in techniques)
        }

        test("using Busoshoku costs energy records use and prepares finisher") {
            val before = poweredWorld()
            val prepared = PowerTechniqueEngine.prepare(before, "p1", "HAKI_BUSOSHOKU")
            assertEquals(CombatActionType.HAKI_BUSOSHOKU, prepared.combatAction)
            assertEquals(before.players.getValue("p1").energy - 4, prepared.world.players.getValue("p1").energy)
            assertEquals(1, prepared.world.players.getValue("p1").profile!!.haki.disciplines.getValue(HakiType.BUSOSHOKU).useCount)
            assertTrue(prepared.bonusDamage > 0)
        }

        test("seastone suppresses Devil Fruit technique") {
            val world = poweredWorld().copy(worldFlags = mapOf("status.p1.seastone" to "true"))
            var rejected = false
            try { PowerTechniqueEngine.prepare(world, "p1", "DEVIL_FRUIT") } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }

    private fun poweredWorld(): WorldState {
        val base = (CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success).profile
        val profile = base.copy(
            haki = HakiState(disciplines = mapOf(
                HakiType.BUSOSHOKU to HakiDiscipline(2),
                HakiType.KENBUNSHOKU to HakiDiscipline(1),
            )),
            devilFruit = DevilFruitState("pulse-pulse", DevilFruitCategory.PARAMECIA, "Fruta Pulso-Pulso", mastery = 1),
        )
        return WorldState(
            campaignId = "technique",
            players = mapOf(
                "p1" to PlayerState("p1", profile.name, profile.maxHp, profile.maxHp, 0, profile.maxEnergy, profile.maxEnergy, profile),
                "p2" to PlayerState("p2", "Mako", 30, 30, 0),
            ),
        )
    }
}
