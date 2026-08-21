package grandlineduo.game

import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.*
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.game.scenario.StormglassCayScenario

data class StormglassRestoredState(
    val scenario: ScenarioState,
    val combat: CombatState?,
)

object StormglassPersistenceAdapter {
    private const val PREFIX = "sg."
    private val HUMAN_PLAYER_IDS = setOf("p1", "p2", "p3", "p4")

    fun encode(
        world: WorldState,
        scenario: ScenarioState,
        combat: CombatState?,
    ): WorldState {
        val flags = world.worldFlags.filterKeys { !it.startsWith(PREFIX) }.toMutableMap()
        flags["sg.stage"] = scenario.stage.name
        if (scenario.participantIds != StormglassCayScenario.LEGACY_PARTICIPANTS) {
            scenario.participantIds.sorted().forEach { flags["sg.participant.$it"] = "1" }
        }
        scenario.sharedFlags.sorted().forEach { flags["sg.shared.$it"] = "1" }
        scenario.privateKnowledge.toSortedMap().forEach { (playerId, knowledge) ->
            knowledge.sorted().forEach { flags["sg.private.$playerId.$it"] = "1" }
        }
        scenario.actedThisStage.sorted().forEach { flags["sg.acted.$it"] = "1" }

        var players = world.players
        if (combat != null) {
            flags["sg.combat"] = "1"
            flags["sg.combat.round"] = combat.round.toString()
            flags["sg.combat.enemy.id"] = combat.enemy.id
            flags["sg.combat.enemy.name"] = combat.enemy.name
            flags["sg.combat.enemy.hp"] = combat.enemy.hp.toString()
            flags["sg.combat.enemy.maxHp"] = combat.enemy.maxHp.toString()
            flags["sg.combat.enemy.attackPower"] = combat.enemy.attackPower.toString()
            flags["sg.combat.telegraph.type"] = combat.telegraph.type.name
            flags["sg.combat.telegraph.target"] = combat.telegraph.targetPlayerId
            flags["sg.combat.status"] = combat.status.name
            combat.lockedActions.toSortedMap().forEach { (playerId, action) ->
                flags["sg.combat.action.$playerId"] = action.type.name
            }
            combat.players.forEach { (playerId, fighter) ->
                val existing = players[playerId]
                    ?: throw IllegalArgumentException("Combat player $playerId missing from world state")
                players = players + (playerId to existing.copy(hp = fighter.hp, maxHp = fighter.maxHp))
            }
        }
        return world.copy(players = players, worldFlags = flags)
    }

    fun decode(world: WorldState): StormglassRestoredState {
        val flags = world.worldFlags
        val stage = flags["sg.stage"]?.let(ScenarioStage::valueOf) ?: ScenarioStage.ARRIVAL
        val shared = flags.keys
            .filter { it.startsWith("sg.shared.") && flags[it] == "1" }
            .map { it.removePrefix("sg.shared.") }
            .toSet()
        val acted = flags.keys
            .filter { it.startsWith("sg.acted.") && flags[it] == "1" }
            .map { it.removePrefix("sg.acted.") }
            .toSet()
        val persistedParticipants = flags.keys
            .filter { it.startsWith("sg.participant.") && flags[it] == "1" }
            .map { it.removePrefix("sg.participant.") }
            .filter { it in HUMAN_PLAYER_IDS }
            .toSortedSet()
        val createdParticipants = world.players.values
            .filter { it.playerId in HUMAN_PLAYER_IDS && it.profile != null }
            .map { it.playerId }
            .toSortedSet()
        val participants = when {
            persistedParticipants.isNotEmpty() -> persistedParticipants
            stage == ScenarioStage.ARRIVAL && acted.isEmpty() &&
                createdParticipants.size in 2..4 &&
                "p1" in createdParticipants && "p2" in createdParticipants -> createdParticipants
            else -> StormglassCayScenario.LEGACY_PARTICIPANTS
        }
        val privateKnowledge = participants.associateWith { playerId ->
            val prefix = "sg.private.$playerId."
            flags.keys
                .filter { it.startsWith(prefix) && flags[it] == "1" }
                .map { it.removePrefix(prefix) }
                .toSet()
        }
        val scenario = ScenarioState(
            stage = stage,
            sharedFlags = shared,
            privateKnowledge = privateKnowledge,
            actedThisStage = acted,
            participantIds = participants,
        )

        val combat = if (flags["sg.combat"] == "1") {
            val combatPlayers = scenario.participantIds.associateWith { playerId ->
                val player = world.players[playerId]
                    ?: throw IllegalArgumentException("Legacy combat player $playerId missing from world state")
                Combatant(playerId, player.name, player.hp, player.maxHp)
            }
            val actions = combatPlayers.keys.mapNotNull { playerId ->
                flags["sg.combat.action.$playerId"]?.let { type ->
                    playerId to CombatAction(playerId, CombatActionType.valueOf(type))
                }
            }.toMap()
            CombatState(
                round = requiredInt(flags, "sg.combat.round"),
                players = combatPlayers,
                enemy = EnemyCombatant(
                    id = required(flags, "sg.combat.enemy.id"),
                    name = required(flags, "sg.combat.enemy.name"),
                    hp = requiredInt(flags, "sg.combat.enemy.hp"),
                    maxHp = requiredInt(flags, "sg.combat.enemy.maxHp"),
                    attackPower = requiredInt(flags, "sg.combat.enemy.attackPower"),
                ),
                telegraph = EnemyTelegraph(
                    type = EnemyAttackType.valueOf(required(flags, "sg.combat.telegraph.type")),
                    targetPlayerId = required(flags, "sg.combat.telegraph.target"),
                ),
                lockedActions = actions,
                status = CombatStatus.valueOf(required(flags, "sg.combat.status")),
            )
        } else null

        return StormglassRestoredState(scenario, combat)
    }

    private fun required(flags: Map<String, String>, key: String): String =
        flags[key] ?: throw IllegalArgumentException("Missing persisted key $key")

    private fun requiredInt(flags: Map<String, String>, key: String): Int =
        required(flags, key).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid integer persisted at $key")
}
