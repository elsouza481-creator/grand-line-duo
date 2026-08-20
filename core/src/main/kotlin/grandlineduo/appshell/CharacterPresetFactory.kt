package grandlineduo.appshell

import grandlineduo.game.character.Attribute
import grandlineduo.game.character.CharacterDraft
import grandlineduo.game.character.Skill

data class CharacterBuildPreset(val id: String, val label: String)

object CharacterPresetFactory {
    private val attributes = linkedMapOf(
        "EQUILIBRADO" to mapOf(Attribute.FOR to 2, Attribute.DES to 2, Attribute.CON to 2, Attribute.INT to 1, Attribute.PER to 1, Attribute.CAR to 1, Attribute.VON to 1),
        "FORTE" to mapOf(Attribute.FOR to 4, Attribute.DES to 1, Attribute.CON to 2, Attribute.INT to 0, Attribute.PER to 1, Attribute.CAR to 0, Attribute.VON to 2),
        "AGIL" to mapOf(Attribute.FOR to 1, Attribute.DES to 4, Attribute.CON to 1, Attribute.INT to 1, Attribute.PER to 2, Attribute.CAR to 0, Attribute.VON to 1),
        "MENTE" to mapOf(Attribute.FOR to 0, Attribute.DES to 1, Attribute.CON to 1, Attribute.INT to 4, Attribute.PER to 2, Attribute.CAR to 1, Attribute.VON to 1),
        "VONTADE" to mapOf(Attribute.FOR to 1, Attribute.DES to 1, Attribute.CON to 2, Attribute.INT to 1, Attribute.PER to 1, Attribute.CAR to 0, Attribute.VON to 4),
        "SOCIAL" to mapOf(Attribute.FOR to 0, Attribute.DES to 1, Attribute.CON to 1, Attribute.INT to 1, Attribute.PER to 1, Attribute.CAR to 4, Attribute.VON to 2),
    )

    private val skills = mapOf(
        "ESPADACHIM" to mapOf(Skill.BLADED_WEAPONS to 2, Skill.ATHLETICS to 2, Skill.ACROBATICS to 2, Skill.PERCEPTION to 2),
        "LUTADOR" to mapOf(Skill.UNARMED_COMBAT to 2, Skill.ATHLETICS to 2, Skill.ACROBATICS to 2, Skill.INSIGHT to 2),
        "ATIRADOR" to mapOf(Skill.FIREARMS to 2, Skill.PERCEPTION to 2, Skill.STEALTH to 2, Skill.ACROBATICS to 2),
        "NAVEGADOR" to mapOf(Skill.NAVIGATION to 2, Skill.SURVIVAL to 2, Skill.PERCEPTION to 2, Skill.WORLD_KNOWLEDGE to 2),
        "MEDICO" to mapOf(Skill.MEDICINE to 2, Skill.INSIGHT to 2, Skill.PERCEPTION to 2, Skill.WORLD_KNOWLEDGE to 2),
        "ENGENHEIRO" to mapOf(Skill.ENGINEERING to 2, Skill.CARPENTRY to 2, Skill.INVESTIGATION to 2, Skill.WORLD_KNOWLEDGE to 2),
        "CHARLATAO" to mapOf(Skill.DECEPTION to 2, Skill.PERSUASION to 2, Skill.INSIGHT to 2, Skill.PERFORMANCE to 2),
    )

    fun attributePresets(): List<CharacterBuildPreset> = listOf(
        CharacterBuildPreset("EQUILIBRADO", "Equilibrado"),
        CharacterBuildPreset("FORTE", "Força e resistência"),
        CharacterBuildPreset("AGIL", "Agilidade e percepção"),
        CharacterBuildPreset("MENTE", "Intelecto e investigação"),
        CharacterBuildPreset("VONTADE", "Vontade e resistência"),
        CharacterBuildPreset("SOCIAL", "Carisma e presença"),
    )

    fun skillPresets(): List<CharacterBuildPreset> = listOf(
        CharacterBuildPreset("ESPADACHIM", "Espadachim"),
        CharacterBuildPreset("LUTADOR", "Lutador"),
        CharacterBuildPreset("ATIRADOR", "Atirador"),
        CharacterBuildPreset("NAVEGADOR", "Navegador"),
        CharacterBuildPreset("MEDICO", "Médico"),
        CharacterBuildPreset("ENGENHEIRO", "Engenheiro"),
        CharacterBuildPreset("CHARLATAO", "Negociador"),
    )

    fun createDraft(
        name: String,
        age: Int,
        origin: String,
        profession: String,
        combatStyle: String,
        attributePreset: String,
        skillPreset: String,
        hair: String,
        skin: String,
        outfit: String,
        accent: String,
    ): CharacterDraft {
        val attributeMap = attributes[attributePreset] ?: attributes.getValue("EQUILIBRADO")
        val skillMap = skills[skillPreset] ?: skills.getValue("ESPADACHIM")
        return CharacterDraft(
            name = name.trim(),
            age = age,
            origin = origin,
            appearance = "hair=$hair;skin=$skin;outfit=$outfit;accent=$accent",
            personality = "Adaptável, competitivo e leal à própria tripulação",
            dream = "Deixar um nome impossível de apagar da Grand Line",
            fear = "Perder a tripulação por uma decisão errada",
            profession = profession,
            combatStyle = combatStyle,
            background = "Criado em $origin e acostumado a sobreviver entre portos e rumores",
            motivation = "Liberdade, descoberta e proteção da tripulação",
            pirateRelation = "Julga cada tripulação pelos próprios atos",
            marineRelation = "Desconfia da autoridade, mas respeita indivíduos honrados",
            importantPerson = "Uma pessoa do porto natal que ainda espera seu retorno",
            defect = "Insiste em assumir riscos quando alguém da tripulação está ameaçado",
            attributes = attributeMap,
            skills = skillMap,
        )
    }
}
