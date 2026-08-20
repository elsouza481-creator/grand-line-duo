package grandlineduo.appshell

import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
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
