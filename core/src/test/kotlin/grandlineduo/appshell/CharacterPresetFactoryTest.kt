package grandlineduo.appshell

import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.ClassPath
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object CharacterPresetFactoryTest {
    fun register() {
        test("all character build presets satisfy hardcore creation budgets") {
            for (preset in CharacterPresetFactory.attributePresets()) {
                val draft = CharacterPresetFactory.createDraft(
                    name = "Namiro",
                    age = 21,
                    origin = "East Blue",
                    profession = "Navegador",
                    combatStyle = "Espadachim",
                    attributePreset = preset.id,
                    skillPreset = "ESPADACHIM",
                    hair = "Curto",
                    skin = "Média",
                    outfit = "Marinheiro",
                    accent = "Vermelho",
                )
                assertTrue(CharacterCreation.create(draft) is CharacterCreationResult.Success, "invalid preset ${preset.id}")
                assertEquals(10, draft.attributes.values.sum())
                assertEquals(8, draft.skills.values.sum())
            }
        }

        test("every skill preset selects the matching gameplay class") {
            val expected = mapOf(
                "ESPADACHIM" to ClassPath.SWORDSMAN,
                "LUTADOR" to ClassPath.BRAWLER,
                "ATIRADOR" to ClassPath.GUNNER,
                "NAVEGADOR" to ClassPath.NAVIGATOR,
                "MEDICO" to ClassPath.DOCTOR,
                "ENGENHEIRO" to ClassPath.SHIPWRIGHT,
                "COZINHEIRO" to ClassPath.COOK,
                "LADINO" to ClassPath.ROGUE,
                "ERUDITO" to ClassPath.SCHOLAR,
                "CAPITAO" to ClassPath.CAPTAIN,
            )
            assertEquals(expected.keys, CharacterPresetFactory.skillPresets().map { it.id }.toSet())

            expected.forEach { (presetId, classPath) ->
                val draft = CharacterPresetFactory.createDraft(
                    name = "Namiro",
                    age = 21,
                    origin = "East Blue",
                    profession = "Aventureiro",
                    combatStyle = presetId,
                    attributePreset = "EQUILIBRADO",
                    skillPreset = presetId,
                    hair = "Curto",
                    skin = "Média",
                    outfit = "Marinheiro",
                    accent = "Vermelho",
                )
                assertEquals(classPath, draft.classPath, "wrong class for $presetId")
                assertEquals(8, draft.skills.values.sum(), "invalid skill budget for $presetId")
                assertTrue(CharacterCreation.create(draft) is CharacterCreationResult.Success, "invalid class preset $presetId")
            }
        }

        test("appearance choices are serialized into the character draft") {
            val draft = CharacterPresetFactory.createDraft(
                name = "Aya", age = 24, origin = "South Blue", profession = "Médica",
                combatStyle = "Lutador", attributePreset = "AGIL", skillPreset = "LUTADOR",
                hair = "Longo", skin = "Escura", outfit = "Casaco", accent = "Azul",
            )
            assertTrue("hair=Longo" in draft.appearance)
            assertTrue("skin=Escura" in draft.appearance)
            assertTrue("outfit=Casaco" in draft.appearance)
            assertTrue("accent=Azul" in draft.appearance)
        }
    }
}
