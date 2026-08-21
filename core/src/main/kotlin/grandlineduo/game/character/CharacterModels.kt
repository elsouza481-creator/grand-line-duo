package grandlineduo.game.character

import grandlineduo.game.powers.DevilFruitState
import grandlineduo.game.powers.HakiState

enum class Attribute {
    FOR, DES, CON, INT, PER, CAR, VON,
}

enum class Skill {
    ATHLETICS,
    ACROBATICS,
    STEALTH,
    PERCEPTION,
    INVESTIGATION,
    SURVIVAL,
    NAVIGATION,
    MEDICINE,
    ENGINEERING,
    CARPENTRY,
    COOKING,
    HISTORY,
    WORLD_KNOWLEDGE,
    DECEPTION,
    PERSUASION,
    INTIMIDATION,
    INSIGHT,
    PERFORMANCE,
    THIEVERY,
    BLADED_WEAPONS,
    FIREARMS,
    UNARMED_COMBAT,
}

data class CharacterDraft(
    val name: String,
    val age: Int,
    val origin: String,
    val appearance: String,
    val personality: String,
    val dream: String,
    val fear: String,
    val profession: String,
    val combatStyle: String,
    val background: String,
    val motivation: String,
    val pirateRelation: String,
    val marineRelation: String,
    val importantPerson: String,
    val defect: String,
    val attributes: Map<Attribute, Int>,
    val skills: Map<Skill, Int>,
    val classPath: ClassPath? = null,
)

data class CharacterProfile(
    val name: String,
    val age: Int,
    val origin: String,
    val appearance: String,
    val personality: String,
    val dream: String,
    val fear: String,
    val profession: String,
    val combatStyle: String,
    val background: String,
    val motivation: String,
    val pirateRelation: String,
    val marineRelation: String,
    val importantPerson: String,
    val defect: String,
    val attributes: Map<Attribute, Int>,
    val skills: Map<Skill, Int>,
    val evolutionPoints: Int = 0,
    val trainingMarks: Set<String> = emptySet(),
    val haki: HakiState = HakiState(),
    val devilFruit: DevilFruitState? = null,
    val classMastery: ClassMasteryState? = null,
) {
    val maxHp: Int get() = 20 + (attributes.getValue(Attribute.CON) * 5)
    val maxEnergy: Int get() = 10 + (attributes.getValue(Attribute.CON) * 2) + (attributes.getValue(Attribute.VON) * 3)
}

sealed interface CharacterCreationResult {
    data class Success(val profile: CharacterProfile) : CharacterCreationResult
    data class Invalid(val errors: List<String>) : CharacterCreationResult
}
