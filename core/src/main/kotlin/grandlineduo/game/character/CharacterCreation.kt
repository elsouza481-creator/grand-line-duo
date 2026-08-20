package grandlineduo.game.character

object CharacterCreation {
    private const val ATTRIBUTE_BUDGET = 10
    private const val INITIAL_SKILL_BUDGET = 8

    fun create(draft: CharacterDraft): CharacterCreationResult {
        val errors = mutableListOf<String>()

        requireText("name", draft.name, errors)
        if (draft.age !in 1..120) errors += "age must be between 1 and 120"
        requireText("origin", draft.origin, errors)
        requireText("appearance", draft.appearance, errors)
        requireText("personality", draft.personality, errors)
        requireText("dream", draft.dream, errors)
        requireText("fear", draft.fear, errors)
        requireText("profession", draft.profession, errors)
        requireText("combatStyle", draft.combatStyle, errors)
        requireText("background", draft.background, errors)
        requireText("motivation", draft.motivation, errors)
        requireText("pirateRelation", draft.pirateRelation, errors)
        requireText("marineRelation", draft.marineRelation, errors)
        requireText("importantPerson", draft.importantPerson, errors)
        requireText("defect", draft.defect, errors)

        if (draft.attributes.keys != Attribute.entries.toSet()) {
            errors += "all seven attributes must be assigned exactly once"
        }
        if (draft.attributes.values.any { it !in -1..4 }) {
            errors += "initial attribute values must be in -1..4"
        }
        if (draft.attributes.values.sum() != ATTRIBUTE_BUDGET) {
            errors += "creation must spend exactly 10 attribute points"
        }

        if (draft.skills.values.any { it !in 0..2 }) {
            errors += "initial skill ranks must be in 0..2"
        }
        if (draft.skills.values.sum() != INITIAL_SKILL_BUDGET) {
            errors += "creation must spend exactly 8 skill points"
        }

        if (errors.isNotEmpty()) return CharacterCreationResult.Invalid(errors)

        return CharacterCreationResult.Success(
            CharacterProfile(
                name = draft.name.trim(),
                age = draft.age,
                origin = draft.origin.trim(),
                appearance = draft.appearance.trim(),
                personality = draft.personality.trim(),
                dream = draft.dream.trim(),
                fear = draft.fear.trim(),
                profession = draft.profession.trim(),
                combatStyle = draft.combatStyle.trim(),
                background = draft.background.trim(),
                motivation = draft.motivation.trim(),
                pirateRelation = draft.pirateRelation.trim(),
                marineRelation = draft.marineRelation.trim(),
                importantPerson = draft.importantPerson.trim(),
                defect = draft.defect.trim(),
                attributes = Attribute.entries.associateWith { draft.attributes.getValue(it) },
                skills = draft.skills.filterValues { it > 0 }.toSortedMap(compareBy { it.ordinal }),
            )
        )
    }

    private fun requireText(field: String, value: String, errors: MutableList<String>) {
        if (value.isBlank()) errors += "$field is required"
    }
}
