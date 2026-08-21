package grandlineduo.core.network

import grandlineduo.core.events.CampaignEvent
import grandlineduo.game.character.CharacterDraft
import java.security.MessageDigest

sealed interface GameplayWireCommand {
    val commandId: String
    val actorId: String
    fun fingerprint(): String

    data class ScenarioChoice(
        override val commandId: String,
        override val actorId: String,
        val choiceId: String,
    ) : GameplayWireCommand {
        override fun fingerprint(): String = "scenario-choice|$actorId|$choiceId"
    }

    data class CombatAction(
        override val commandId: String,
        override val actorId: String,
        val actionType: String,
    ) : GameplayWireCommand {
        override fun fingerprint(): String = "combat-action|$actorId|$actionType"
    }

    data class CharacterCreate(
        override val commandId: String,
        override val actorId: String,
        val draft: CharacterDraft,
    ) : GameplayWireCommand {
        override fun fingerprint(): String = "character-create|" + CharacterDraftFingerprint.hash(actorId, draft)
    }

    data class VoyageAction(
        override val commandId: String,
        override val actorId: String,
        val actionType: String,
    ) : GameplayWireCommand {
        override fun fingerprint(): String = "voyage-action|$actorId|$actionType"
    }

    data class ArcChoice(
        override val commandId: String,
        override val actorId: String,
        val choiceId: String,
    ) : GameplayWireCommand {
        override fun fingerprint(): String = "arc-choice|$actorId|$choiceId"
    }

    data class InventoryAction(
        override val commandId: String,
        override val actorId: String,
        val actionType: String,
        val target: String,
        val amount: Int = 1,
    ) : GameplayWireCommand {
        override fun fingerprint(): String = "inventory-action|$actorId|$actionType|$target|$amount"
    }

    data class WorldAction(
        override val commandId: String,
        override val actorId: String,
        val actionType: String,
        val target: String = "",
        val amount: Int = 1,
    ) : GameplayWireCommand {
        override fun fingerprint(): String = "world-action|$actorId|$actionType|$target|$amount"
    }

    data class PowerAction(
        override val commandId: String,
        override val actorId: String,
        val techniqueId: String,
    ) : GameplayWireCommand {
        override fun fingerprint(): String = "power-action|$actorId|$techniqueId"
    }

    data class QuestAction(
        override val commandId: String,
        override val actorId: String,
        val actionType: String,
        val questId: String = "",
        val amount: Int = 1,
    ) : GameplayWireCommand {
        override fun fingerprint(): String = "quest-action|$actorId|$actionType|$questId|$amount"
    }

    data class DuelAction(
        override val commandId: String,
        override val actorId: String,
        val actionType: String,
    ) : GameplayWireCommand {
        override fun fingerprint(): String = "duel-action|$actorId|${actionType.uppercase()}"
    }
}

private object CharacterDraftFingerprint {
    fun hash(actorId: String, draft: CharacterDraft): String {
        val canonical = buildString {
            field("actorId", actorId)
            field("name", draft.name)
            field("age", draft.age.toString())
            field("origin", draft.origin)
            field("appearance", draft.appearance)
            field("personality", draft.personality)
            field("dream", draft.dream)
            field("fear", draft.fear)
            field("profession", draft.profession)
            field("combatStyle", draft.combatStyle)
            field("background", draft.background)
            field("motivation", draft.motivation)
            field("pirateRelation", draft.pirateRelation)
            field("marineRelation", draft.marineRelation)
            field("importantPerson", draft.importantPerson)
            field("defect", draft.defect)
            draft.attributes.entries.sortedBy { it.key.ordinal }.forEach { (key, value) ->
                field("attribute", key.name)
                field("attributeValue", value.toString())
            }
            draft.skills.entries.sortedBy { it.key.ordinal }.forEach { (key, value) ->
                field("skill", key.name)
                field("skillValue", value.toString())
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun StringBuilder.field(name: String, value: String) {
        append(name.length).append(':').append(name)
        append(value.length).append(':').append(value).append(';')
    }
}

fun interface GameplayCommandHandler {
    fun handle(command: GameplayWireCommand, hostTimestamp: Long): CampaignEvent
}
