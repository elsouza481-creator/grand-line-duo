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

data class TrainingDuelRecord(
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val forfeits: Int = 0,
) {
    val matches: Int get() = wins + losses + draws
}

object TrainingDuelEngine {
    private const val PREFIX = "duel.active."
    private const val STATUS = "${PREFIX}status"
    private const val CHALLENGER = "${PREFIX}challenger"
    private const val OPPONENT = "${PREFIX}opponent"
    private const val ROUND = "${PREFIX}round"
    private const val HP_PREFIX = "${PREFIX}hp."
    private const val ACTION_PREFIX = "${PREFIX}action."
    private const val LAST_WINNER = "duel.last.winner"
    private const val LAST_ROUND = "duel.last.round"
    private const val RECORD_PREFIX = "duel.record."

    fun state(world: WorldState): TrainingDuelState? {
        val status = world.worldFlags[STATUS]?.let(TrainingDuelStatus::valueOf) ?: return null
        val challenger = world.worldFlags[CHALLENGER] ?: return null
        val opponent = world.worldFlags[OPPONENT] ?: return null
        val round = world.worldFlags[ROUND]?.toIntOrNull() ?: 0
        val participants = linkedSetOf(challenger, opponent)
        val hp = buildMap {
            participants.forEach { playerId ->
                world.worldFlags[hpKey(playerId)]?.toIntOrNull()?.let { put(playerId, it) }
            }
        }
        val actions = buildMap {
            participants.forEach { playerId ->
                world.worldFlags[actionKey(playerId)]?.let {
                    put(playerId, TrainingDuelAction.valueOf(it))
                }
            }
        }
        return TrainingDuelState(status, challenger, opponent, round, hp, actions)
    }

    fun lastWinner(world: WorldState): String? = world.worldFlags[LAST_WINNER]

    fun record(world: WorldState, playerId: String): TrainingDuelRecord {
        require(playerId in HUMAN_PLAYER_IDS) { "Unknown duel player $playerId" }
        return TrainingDuelRecord(
            wins = recordValue(world, playerId, "wins"),
            losses = recordValue(world, playerId, "losses"),
            draws = recordValue(world, playerId, "draws"),
            forfeits = recordValue(world, playerId, "forfeits"),
        )
    }

    fun challenge(world: WorldState, actorId: String): WorldState =
        challenge(world, actorId, legacyOpponent(actorId))

    fun challenge(world: WorldState, actorId: String, opponentId: String): WorldState {
        requireHuman(world, actorId)
        requireHuman(world, opponentId)
        require(actorId != opponentId) { "A player cannot challenge themselves" }
        require(state(world) == null) { "A training duel is already pending or active" }
        requireBothAtTraining(world, actorId, opponentId)
        return world.copy(
            worldFlags = world.worldFlags + mapOf(
                STATUS to TrainingDuelStatus.CHALLENGED.name,
                CHALLENGER to actorId,
                OPPONENT to opponentId,
            ),
        )
    }

    fun accept(world: WorldState, actorId: String): WorldState {
        val duel = requireNotNull(state(world)) { "No training duel challenge is pending" }
        require(duel.status == TrainingDuelStatus.CHALLENGED) { "Training duel is already active" }
        require(actorId == duel.opponentId) { "Only the challenged player can accept" }
        requireBothAtTraining(world, duel.challengerId, duel.opponentId)
        val challenger = world.players.getValue(duel.challengerId)
        val opponent = world.players.getValue(duel.opponentId)
        val flags = clearActions(world.worldFlags) + mapOf(
            STATUS to TrainingDuelStatus.ACTIVE.name,
            CHALLENGER to duel.challengerId,
            OPPONENT to duel.opponentId,
            ROUND to "1",
            hpKey(duel.challengerId) to challenger.maxHp.toString(),
            hpKey(duel.opponentId) to opponent.maxHp.toString(),
        )
        return world.copy(worldFlags = flags)
    }

    fun decline(world: WorldState, actorId: String): WorldState {
        val duel = requireNotNull(state(world)) { "No training duel challenge is pending" }
        require(duel.status == TrainingDuelStatus.CHALLENGED) { "Active duel cannot be declined" }
        require(actorId == duel.opponentId) { "Only the challenged player can decline" }
        return world.copy(worldFlags = clearActive(world.worldFlags))
    }

    fun cancel(world: WorldState, actorId: String): WorldState {
        val duel = requireNotNull(state(world)) { "No training duel challenge is pending" }
        require(duel.status == TrainingDuelStatus.CHALLENGED) { "Active duel cannot be cancelled" }
        require(actorId == duel.challengerId) { "Only the challenger can cancel the duel" }
        return world.copy(worldFlags = clearActive(world.worldFlags))
    }

    fun submitAction(world: WorldState, actorId: String, action: TrainingDuelAction): WorldState {
        val duel = requireNotNull(state(world)) { "No active training duel" }
        require(duel.status == TrainingDuelStatus.ACTIVE) { "Training duel has not been accepted" }
        require(actorId in participants(duel)) { "Player is not part of this duel" }
        requireBothAtTraining(world, duel.challengerId, duel.opponentId)
        require(actorId !in duel.lockedActions) { "Duel action already locked for this round" }

        val lockedWorld = world.copy(worldFlags = world.worldFlags + (actionKey(actorId) to action.name))
        val locked = requireNotNull(state(lockedWorld))
        if (locked.lockedActions.size < 2) return lockedWorld
        return resolveRound(lockedWorld, locked)
    }

    fun forfeit(world: WorldState, actorId: String): WorldState {
        val duel = requireNotNull(state(world)) { "No active training duel" }
        require(duel.status == TrainingDuelStatus.ACTIVE) { "Training duel has not started" }
        require(actorId in participants(duel)) { "Player is not part of this duel" }
        return finish(
            world = world,
            duel = duel,
            winner = otherParticipant(duel, actorId),
            round = duel.round,
            forfeitingPlayerId = actorId,
        )
    }

    fun blocksWorldMovement(world: WorldState): Boolean = state(world) != null

    private fun resolveRound(world: WorldState, duel: TrainingDuelState): WorldState {
        val challengerId = duel.challengerId
        val opponentId = duel.opponentId
        val challengerAction = duel.lockedActions.getValue(challengerId)
        val opponentAction = duel.lockedActions.getValue(opponentId)
        val challengerHp = duel.duelHp.getValue(challengerId)
        val opponentHp = duel.duelHp.getValue(opponentId)
        val damageToChallenger = damage(world, opponentId, challengerId, opponentAction, challengerAction)
        val damageToOpponent = damage(world, challengerId, opponentId, challengerAction, opponentAction)
        val nextChallenger = (challengerHp - damageToChallenger).coerceAtLeast(0)
        val nextOpponent = (opponentHp - damageToOpponent).coerceAtLeast(0)

        if (nextChallenger == 0 || nextOpponent == 0) {
            val winner = when {
                nextChallenger == 0 && nextOpponent == 0 -> "DRAW"
                nextChallenger == 0 -> opponentId
                else -> challengerId
            }
            return finish(world, duel, winner, duel.round)
        }

        val nextFlags = clearActions(world.worldFlags) + mapOf(
            ROUND to (duel.round + 1).toString(),
            hpKey(challengerId) to nextChallenger.toString(),
            hpKey(opponentId) to nextOpponent.toString(),
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

    private fun finish(
        world: WorldState,
        duel: TrainingDuelState,
        winner: String,
        round: Int,
        forfeitingPlayerId: String? = null,
    ): WorldState {
        var flags = clearActive(world.worldFlags) + mapOf(
            LAST_WINNER to winner,
            LAST_ROUND to round.toString(),
        )
        if (winner == "DRAW") {
            participants(duel).forEach { playerId ->
                flags = incrementRecord(flags, playerId, "draws")
            }
        } else {
            require(winner in participants(duel)) { "Duel winner must be a participant" }
            val loser = otherParticipant(duel, winner)
            flags = incrementRecord(flags, winner, "wins")
            flags = incrementRecord(flags, loser, "losses")
            if (forfeitingPlayerId != null) {
                require(forfeitingPlayerId == loser) { "Only the losing participant can forfeit" }
                flags = incrementRecord(flags, loser, "forfeits")
            }
        }
        return world.copy(worldFlags = flags)
    }

    private fun incrementRecord(flags: Map<String, String>, playerId: String, field: String): Map<String, String> {
        val key = recordKey(playerId, field)
        val current = flags[key]?.toIntOrNull() ?: 0
        return flags + (key to (current + 1).toString())
    }

    private fun recordValue(world: WorldState, playerId: String, field: String): Int =
        world.worldFlags[recordKey(playerId, field)]?.toIntOrNull() ?: 0

    private fun recordKey(playerId: String, field: String) = "$RECORD_PREFIX$playerId.$field"

    private fun clearActions(flags: Map<String, String>): Map<String, String> =
        flags.filterKeys { !it.startsWith(ACTION_PREFIX) }

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
        require(playerId in HUMAN_PLAYER_IDS) { "Unknown duel player $playerId" }
        require(world.players.containsKey(playerId)) { "Duel player $playerId is not present" }
    }

    private fun participants(duel: TrainingDuelState): Set<String> =
        setOf(duel.challengerId, duel.opponentId)

    private fun otherParticipant(duel: TrainingDuelState, playerId: String): String = when (playerId) {
        duel.challengerId -> duel.opponentId
        duel.opponentId -> duel.challengerId
        else -> throw IllegalArgumentException("Player is not part of this duel")
    }

    private fun legacyOpponent(playerId: String): String = when (playerId) {
        "p1" -> "p2"
        "p2" -> "p1"
        else -> throw IllegalArgumentException("Four-player duel challenge requires an explicit opponent")
    }

    private fun hpKey(playerId: String) = "$HP_PREFIX$playerId"
    private fun actionKey(playerId: String) = "$ACTION_PREFIX$playerId"

    private val HUMAN_PLAYER_IDS = setOf("p1", "p2", "p3", "p4")
}
