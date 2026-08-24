package grandlineduo.game.duel

import grandlineduo.game.combat.CombatAction

enum class DuelPhase { PENDING, ACTIVE, FINISHED }
enum class DuelFinishReason { KNOCKOUT, DOUBLE_KNOCKOUT }

data class DuelFighter(
    val id: String,
    val name: String,
    val hp: Int,
    val maxHp: Int,
)

data class DuelState(
    val duelId: String,
    val challengerId: String,
    val challengedId: String,
    val phase: DuelPhase,
    val round: Int = 0,
    val fighters: Map<String, DuelFighter> = emptyMap(),
    val lockedActions: Map<String, CombatAction> = emptyMap(),
    val setupReady: Set<String> = emptySet(),
    val winnerId: String? = null,
    val loserId: String? = null,
    val finishReason: DuelFinishReason? = null,
)

data class DuelRoundResult(
    val state: DuelState,
    val damageTaken: Map<String, Int>,
    val log: List<String>,
)

class DuelRuleException(message: String) : RuntimeException(message)
