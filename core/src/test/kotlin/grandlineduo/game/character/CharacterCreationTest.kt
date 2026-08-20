package grandlineduo.game.character

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object CharacterCreationTest {
    fun register() {
        test("valid character creation derives hp and energy from CON and VON") {
            val result = CharacterCreation.create(validDraft())
            assertTrue(result is CharacterCreationResult.Success)
            val profile = (result as CharacterCreationResult.Success).profile
            assertEquals(30, profile.maxHp)
            assertEquals(17, profile.maxEnergy)
            assertEquals(10, profile.attributes.values.sum())
            assertEquals(8, profile.skills.values.sum())
        }

        test("character creation rejects attribute budget other than exactly ten") {
            val draft = validDraft().copy(attributes = validDraft().attributes + (Attribute.FOR to 2))
            val result = CharacterCreation.create(draft)
            assertTrue(result is CharacterCreationResult.Invalid)
            assertTrue((result as CharacterCreationResult.Invalid).errors.any { it.contains("10 attribute") })
        }

        test("character creation rejects attributes outside minus one to plus four") {
            val tooHigh = validDraft().copy(attributes = validDraft().attributes + (Attribute.DES to 5))
            val result = CharacterCreation.create(tooHigh)
            assertTrue(result is CharacterCreationResult.Invalid)
            assertTrue((result as CharacterCreationResult.Invalid).errors.any { it.contains("-1..4") })
        }

        test("character creation rejects invalid initial skill allocation") {
            val tooMany = validDraft().copy(skills = validDraft().skills + (Skill.ATHLETICS to 4))
            val result = CharacterCreation.create(tooMany)
            assertTrue(result is CharacterCreationResult.Invalid)
            val errors = (result as CharacterCreationResult.Invalid).errors
            assertTrue(errors.any { it.contains("8 skill") })
            assertTrue(errors.any { it.contains("0..2") })
        }

        test("character creation requires narrative identity and a defect") {
            val draft = validDraft().copy(dream = "", defect = "   ")
            val result = CharacterCreation.create(draft)
            assertTrue(result is CharacterCreationResult.Invalid)
            val errors = (result as CharacterCreationResult.Invalid).errors
            assertTrue(errors.any { it.contains("dream") })
            assertTrue(errors.any { it.contains("defect") })
        }
    }

    internal fun validDraft() = CharacterDraft(
        name = "Kairo",
        age = 19,
        origin = "East Blue",
        appearance = "Cabelo escuro e casaco azul",
        personality = "Impulsivo, leal e curioso",
        dream = "Encontrar uma ilha que não existe nos mapas",
        fear = "Perder o parceiro por uma decisão própria",
        profession = "Navegador aprendiz",
        combatStyle = "Espadachim de uma lâmina",
        background = "Cresceu entre contrabandistas portuários",
        motivation = "Provar que o mar não pertence ao Governo",
        pirateRelation = "Desconfiado, mas fascinado pela liberdade",
        marineRelation = "Hostil após uma prisão injusta",
        importantPerson = "Mestre Orin",
        defect = "Assume riscos antes de ouvir o plano inteiro",
        attributes = mapOf(
            Attribute.FOR to 1,
            Attribute.DES to 2,
            Attribute.CON to 2,
            Attribute.INT to 1,
            Attribute.PER to 2,
            Attribute.CAR to 1,
            Attribute.VON to 1,
        ),
        skills = mapOf(
            Skill.ATHLETICS to 1,
            Skill.ACROBATICS to 1,
            Skill.PERCEPTION to 2,
            Skill.NAVIGATION to 2,
            Skill.BLADED_WEAPONS to 2,
        ),
    )
}
