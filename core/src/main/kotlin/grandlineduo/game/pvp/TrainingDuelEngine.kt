package grandlineduo.game.pvp

import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.CombatModifierResolver
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction

enum class TrainingDuelStatus {
    CHALLENGED,
    ACTIVE,
}

enum class TrainingDuelAction {
    ATTACK,
    DEFEND,
    DODGE,
}

data class TrainingDuelState(
    val status: TrainingDuelStatus,
    val challengerId: String,
    val opponentId: String,
    val round: Int = 0,
    val duelHp: Map<String, Int> = emptyMap(),
    val lockedActions: Map<String, TrainingDuelAction> = emptyMap(),
)

object TrainingDuelEngine {
    private const val PREFIX = "duel.active."
    private const val STATUS = "${PREFIX}status"
    private const val CHALLENGER = "${PREFIX}challenger"
    private const val OPPONENT = "${PREFIX}opponent"
    private const val ROUND = "${PREFIX}round"
    private const val HP_P1 = "${PREFIX}hp.p1"
    private const val HP_P2 = "${PREFIX}hp.p2"
    private const val ACTION_P1 = "${PREFIX}action.p1"
    private const val ACTION_P2 = "${PREFIX}action.p2"
    private const val LAST_WINNER = "duel.last.winner"
    private const val LAST_ROUND = "duel.last.round"

    fun state(world: WorldState): TrainingDuelState? {
        val status = world.worldFlags[STATUS]?.let(TrainingDuelStatus::valueOf) ?: return null
        val challenger = world.worldFlags[CHALLENGER] ?: return null
        val opponent = world.worldFlags[OPPONENT] ?: return null
        val round = world.worldFlags[ROUND]?.toIntOrNull() ?: 0
        val hp = buildMap {
            world.worldFlags[HP_P1]?.toIntOrNull()?.let { put("p1", it) }
            world.worldFlags[HP_P2]?.toIntOrNull()?.let { put("p2", it) }
        }
        val actions = buildMap {
            world.worldFlags[ACTION_P1]?.let { put("p1", TrainingDuelAction.valueOf(it)) }
            world.worldFlags[ACTION_P2]?.let { put("p2", TrainingDuelAction.valueOf(it)) }
        }
        return TrainingDuelState(status, challenger, opponent, round, hp, actions)
    }

    fun lastWinner(world: WorldState): String? = world.worldFlags[LAST_WINNER]

    fun challenge(world: WorldState, actorId: String): WorldState {
        requireHuman(world, actorId)
        require(state(world) == null) { "A training duel is already pending or active" }
        val opponent = other(actorId)
        requireHuman(world, opponent)
        requireBothAtTraining(world, actorId, opponent)
        return world.copy(
            worldFlags = world.worldFlags + mapOf(
                STATUS to TrainingDuelStatus.CHALLENGED.name,
                CHALLENGER to actorId,
                OPPONENT to opponent,
            ),
        )
    }

    fun accept(world: WorldState, actorId: String): WorldState {
        val duel = requireNotNull(state(world)) { "No training duel challenge is pending" }
        require(duel.status == TrainingDuelStatus.CHALLENGED) { "Training duel is already active" }
        require(actorId == duel.opponentId) { "Only the challenged player can accept" }
        requireBothAtTraining(world, duel.challengerId, duel.opponentId)
        val p1 = world.players.getValue("p1")
        val p2 = world.players.getValue("p2")
        val flags = clearActions(world.worldFlags) + mapOf(
            STATUS to TrainingDuelStatus.ACTIVE.name,
            CHALLENGER to duel.challengerId,
            OPPONENT to duel.opponentId,
            ROUND to "1",
            HP_P1 to p1.maxHp.toString(),
            HP_P2 to p2.maxHp.toString(),
        )
        return world.copy(worldFlags = flags)
    }

    fun decline(world: WorldState, actorId: String): WorldState {
        val duel = requireNotNull(state(world)) { "No training duel challenge is pending" }
        require(duel.status == TrainingDuelStatus.CHALLENGED) { "Active duel cannot be declined" }
        require(actorId == duel.opponentId) { "Only the challenged player can decline" }
        return world.copy(worldFlags = clearActive(world.worldFlags))
    }

    fun submitAction(world: WorldState, actorId: String, action: TrainingDuelAction): WorldState {
        val duel = requireNotNull(state(world)) { "No active training duel" }
        require(duel.status == TrainingDuelStatus.ACTIVE) { "Training duel has not been accepted" }
        require(actorId == duel.challengerId || actorId == duel.opponentId) { "Player is not part of this duel" }
        requireBothAtTraining(world, duel.challengerId, duel.opponentId)
        require(actorId !in duel.lockedActions) { "Duel action already locked for this round" }

        val actionKey = if (actorId == "p1") ACTION_P1 else ACTION_P2
        val lockedWorld = world.copy(worldFlags = world.worldFlags + (actionKey to action.name))
        val locked = requireNotNull(state(lockedWorld))
        if (locked.lockedActions.size < 2) return lockedWorld
        return resolveRound(lockedWorld, locked)
    }

    fun forfeit(world: WorldState, actorId: String): WorldState {
        val duel = requireNotNull(state(world)) { "No active training duel" }
        require(duel.status == TrainingDuelStatus.ACTIVE) { "Training duel has not started" }
        require(actorId == duel.challengerId || actorId == duel.opponentId) { "Player is not part of this duel" }
        return finish(world, other(actorId), duel.round)
    }

    fun blocksWorldMovement(world: WorldState): Boolean = state(world) != null

    private fun resolveRound(world: WorldState, duel: TrainingDuelState): WorldState {
        val p1Action = duel.lockedActions.getValue("p1")
        val p2Action = duel.lockedActions.getValue("p2")
        val p1Hp = duel.duelHp.getValue("p1")
        val p2Hp = duel.duelHp.getValue("p2")
        val damageToP1 = damage(world, "p2", "p1", p2Action, p1Action)
        val damageToP2 = damage(world, "p1", "p2", p1Action, p2Action)
        val nextP1 = (p1Hp - damageToP1).coerceAtLeast(0)
        val nextP2 = (p2Hp - damageToP2).coerceAtLeast(0)

        if (nextP1 == 0 || nextP2 == 0) {
            val winner = when {
                nextP1 == 0 && nextP2 == 0 -> "DRAW"
                nextP1 == 0 -> "p2"
                else -> "p1"
            }
            return finish(world, winner, duel.round)
        }

        val nextFlags = clearActions(world.worldFlags) + mapOf(
            ROUND to (duel.round + 1).toString(),
            HP_P1 to nextP1.toString(),
            HP_P2 to nextP2.toString(),
        )
        return world.copy(worldFlags = nextFlags)
    }

    private fun damage(
        world: WorldState,
        attackerId: String,
        defenderId: String,
        attackerAction: TrainingDuelAction,
        defenderAction: TrainingDuelAction,
    ): Int {
        if (attackerAction != TrainingDuelAction.ATTACK) return 0
        if (defenderAction == TrainingDuelAction.DODGE) return 0
        val modifiers = CombatModifierResolver.forWorld(world)
        val attack = 8 + modifiers.getValue(attackerId).attackBonus
        val reduction = modifiers.getValue(defenderId).damageReduction +
            if (defenderAction == TrainingDuelAction.DEFEND) 5 else 0
        return (attack - reduction).coerceAtLeast(1)
    }

    private fun finish(world: WorldState, winner: String, round: Int): WorldState = world.copy(
        worldFlags = clearActive(world.worldFlags) + mapOf(
            LAST_WINNER to winner,
            LAST_ROUND to round.toString(),
        ),
    )

    private fun clearActions(flags: Map<String, String>): Map<String, String> =
        flags - ACTION_P1 - ACTION_P2

    private fun clearActive(flags: Map<String, String>): Map<String, String> =
        flags.filterKeys { !it.startsWith(PREFIX) }

    private fun requireBothAtTraining(world: WorldState, firstId: String, secondId: String) {
        require(ExplorationEngine.interactionAt(world, firstId) == ExplorationInteraction.TRAINING) {
            "$firstId must be at the physical training area"
        }
        require(ExplorationEngine.interactionAt(world, secondId) == ExplorationInteraction.TRAINING) {
            "$secondId must be at the physical training area"
        }
    }

    private fun requireHuman(world: WorldState, playerId: String) {
        require(playerId == "p1" || playerId == "p2") { "Unknown duel player $playerId" }
        require(world.players.containsKey(playerId)) { "Duel player $playerId is not present" }
    }

    private fun other(playerId: String): String = when (playerId) {
        "p1" -> "p2"
        "p2" -> "p1"
        else -> throw IllegalArgumentException("Unknown duel player $playerId")
    }
}
