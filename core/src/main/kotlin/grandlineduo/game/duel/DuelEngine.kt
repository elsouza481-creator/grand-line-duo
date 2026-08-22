package grandlineduo.game.duel

import grandlineduo.game.combat.CombatAction
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatModifiers
import java.util.Random

class DuelEngine(
    private val seed: Long,
    private val modifiers: Map<String, CombatModifiers> = emptyMap(),
) {
    fun lockAction(state: DuelState, action: CombatAction): DuelState {
        if (state.phase != DuelPhase.ACTIVE) throw DuelRuleException("Duel is not active")
        val fighter = state.fighters[action.playerId]
            ?: throw DuelRuleException("Unknown duel fighter ${action.playerId}")
        if (fighter.hp <= 0) throw DuelRuleException("${action.playerId} is down")
        if (action.playerId in state.lockedActions) {
            throw DuelRuleException("${action.playerId} already locked an action")
        }
        return state.copy(lockedActions = state.lockedActions + (action.playerId to action))
    }

    fun resolveIfReady(state: DuelState): DuelRoundResult? {
        if (state.phase != DuelPhase.ACTIVE) throw DuelRuleException("Duel is not active")
        val fighterIds = state.fighters.keys.sorted()
        if (fighterIds.size != 2) throw DuelRuleException("Active duel requires exactly two fighters")
        if (!state.lockedActions.keys.containsAll(fighterIds)) return null
        if (state.lockedActions.keys.any { it !in state.fighters }) {
            throw DuelRuleException("Locked action belongs to an unknown fighter")
        }

        val outgoing = fighterIds.associateWith { playerId ->
            offensiveDamage(state, playerId, state.lockedActions.getValue(playerId).type)
        }
        val damageTaken = linkedMapOf<String, Int>()
        val log = mutableListOf<String>()

        fighterIds.forEach { defenderId ->
            val attackerId = fighterIds.first { it != defenderId }
            val attackerAction = state.lockedActions.getValue(attackerId).type
            val defenderAction = state.lockedActions.getValue(defenderId).type
            val defended = defend(
                incoming = outgoing.getValue(attackerId),
                attackerAction = attackerAction,
                defenderAction = defenderAction,
                defenderId = defenderId,
                round = state.round,
            )
            val finalDamage = (defended - (modifiers[defenderId]?.damageReduction ?: 0)).coerceAtLeast(0)
            damageTaken[defenderId] = finalDamage
            if (finalDamage == 0) {
                log += "$defenderId evita o ataque de $attackerId."
            } else {
                log += "$attackerId causa $finalDamage de dano em $defenderId."
            }
        }

        val projectedHp = fighterIds.associateWith { id ->
            state.fighters.getValue(id).hp - damageTaken.getValue(id)
        }
        val knockedOut = fighterIds.filter { projectedHp.getValue(it) <= 0 }
        val updatedFighters = linkedMapOf<String, DuelFighter>()

        when (knockedOut.size) {
            2 -> {
                fighterIds.forEach { id ->
                    updatedFighters[id] = state.fighters.getValue(id).copy(hp = 1)
                }
                return DuelRoundResult(
                    state = state.copy(
                        phase = DuelPhase.FINISHED,
                        fighters = updatedFighters,
                        lockedActions = emptyMap(),
                        setupReady = emptySet(),
                        winnerId = null,
                        loserId = null,
                        finishReason = DuelFinishReason.DOUBLE_KNOCKOUT,
                    ),
                    damageTaken = damageTaken,
                    log = log + "Nocaute duplo. O duelo termina empatado.",
                )
            }
            1 -> {
                val loserId = knockedOut.single()
                val winnerId = fighterIds.first { it != loserId }
                fighterIds.forEach { id ->
                    val nextHp = if (id == loserId) 1 else projectedHp.getValue(id).coerceAtLeast(1)
                    updatedFighters[id] = state.fighters.getValue(id).copy(hp = nextHp)
                }
                return DuelRoundResult(
                    state = state.copy(
                        phase = DuelPhase.FINISHED,
                        fighters = updatedFighters,
                        lockedActions = emptyMap(),
                        setupReady = emptySet(),
                        winnerId = winnerId,
                        loserId = loserId,
                        finishReason = DuelFinishReason.KNOCKOUT,
                    ),
                    damageTaken = damageTaken,
                    log = log + "$winnerId vence o duelo por nocaute.",
                )
            }
            else -> Unit
        }

        fighterIds.forEach { id ->
            updatedFighters[id] = state.fighters.getValue(id).copy(hp = projectedHp.getValue(id))
        }
        val nextSetup = fighterIds
            .filter { state.lockedActions.getValue(it).type == CombatActionType.SETUP }
            .toSet()
        return DuelRoundResult(
            state = state.copy(
                phase = DuelPhase.ACTIVE,
                round = state.round + 1,
                fighters = updatedFighters,
                lockedActions = emptyMap(),
                setupReady = nextSetup,
                winnerId = null,
                loserId = null,
                finishReason = null,
            ),
            damageTaken = damageTaken,
            log = log,
        )
    }

    private fun offensiveDamage(state: DuelState, playerId: String, type: CombatActionType): Int {
        val modifier = modifiers[playerId] ?: CombatModifiers()
        val random = rng(state.round, playerId, DAMAGE_SALT)
        val base = when (type) {
            CombatActionType.ATTACK -> 14 + random.nextInt(5)
            CombatActionType.DEFEND -> 0
            CombatActionType.DODGE -> 0
            CombatActionType.SETUP -> 4 + random.nextInt(3)
            CombatActionType.FINISHER -> 14 + random.nextInt(6)
            CombatActionType.HAKI_BUSOSHOKU -> 18 + random.nextInt(6) + modifier.busoshokuBonus
            CombatActionType.HAKI_KENBUNSHOKU -> 0
            CombatActionType.HAKI_HAOSHOKU -> 16 + random.nextInt(7) + modifier.haoshokuBonus
            CombatActionType.DEVIL_FRUIT -> 16 + random.nextInt(7) + modifier.devilFruitBonus
        }
        val attackBonus = when (type) {
            CombatActionType.ATTACK,
            CombatActionType.SETUP,
            CombatActionType.FINISHER,
            CombatActionType.HAKI_BUSOSHOKU,
            CombatActionType.HAKI_HAOSHOKU,
            CombatActionType.DEVIL_FRUIT -> modifier.attackBonus
            CombatActionType.DEFEND,
            CombatActionType.DODGE,
            CombatActionType.HAKI_KENBUNSHOKU -> 0
        }
        val setupBonus = if (playerId in state.setupReady && isOffensive(type)) {
            if (type == CombatActionType.FINISHER) 12 else 6
        } else 0
        return base + attackBonus + setupBonus
    }

    private fun defend(
        incoming: Int,
        attackerAction: CombatActionType,
        defenderAction: CombatActionType,
        defenderId: String,
        round: Int,
    ): Int {
        if (incoming <= 0) return 0
        return when (defenderAction) {
            CombatActionType.DEFEND -> ((incoming * 35) / 100).coerceAtLeast(1)
            CombatActionType.DODGE -> {
                if (attackerAction in DIRECT_ATTACKS && defenseRoll(round, defenderId) < 65) 0 else incoming
            }
            CombatActionType.HAKI_KENBUNSHOKU -> {
                val threshold = when {
                    attackerAction in DIRECT_ATTACKS -> 85
                    attackerAction == CombatActionType.HAKI_HAOSHOKU -> 50
                    else -> 0
                }
                if (defenseRoll(round, defenderId) < threshold) 0
                else ((incoming * 50) / 100).coerceAtLeast(1)
            }
            else -> incoming
        }
    }

    private fun defenseRoll(round: Int, playerId: String): Int =
        rng(round, playerId, DEFENSE_SALT).nextInt(100)

    private fun rng(round: Int, playerId: String, salt: Long): Random =
        Random(
            seed xor
                (round.toLong() * -7046029254386353131L) xor
                (playerId.hashCode().toLong() * 6364136223846793005L) xor
                salt
        )

    private fun isOffensive(type: CombatActionType): Boolean = type in OFFENSIVE_ACTIONS

    private companion object {
        const val DAMAGE_SALT = 0x13579BDFL
        const val DEFENSE_SALT = 0x2468ACE0L

        val OFFENSIVE_ACTIONS = setOf(
            CombatActionType.ATTACK,
            CombatActionType.SETUP,
            CombatActionType.FINISHER,
            CombatActionType.HAKI_BUSOSHOKU,
            CombatActionType.HAKI_HAOSHOKU,
            CombatActionType.DEVIL_FRUIT,
        )

        val DIRECT_ATTACKS = setOf(
            CombatActionType.ATTACK,
            CombatActionType.SETUP,
            CombatActionType.FINISHER,
            CombatActionType.HAKI_BUSOSHOKU,
            CombatActionType.DEVIL_FRUIT,
        )
    }
}
