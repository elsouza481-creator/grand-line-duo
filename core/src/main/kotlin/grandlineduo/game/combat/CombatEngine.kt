package grandlineduo.game.combat

import java.util.Random

enum class CombatActionType { ATTACK, DEFEND, DODGE, SETUP, FINISHER, HAKI_BUSOSHOKU, HAKI_KENBUNSHOKU, HAKI_HAOSHOKU, DEVIL_FRUIT }
enum class CombatStatus { ACTIVE, VICTORY, DEFEAT }
enum class EnemyAttackType { HEAVY_STRIKE, SWEEP }

data class CombatAction(val playerId: String, val type: CombatActionType)

data class Combatant(
    val id: String,
    val name: String,
    val hp: Int,
    val maxHp: Int,
)

data class EnemyCombatant(
    val id: String,
    val name: String,
    val hp: Int,
    val maxHp: Int,
    val attackPower: Int,
)

data class EnemyTelegraph(
    val type: EnemyAttackType,
    val targetPlayerId: String,
)

data class CombatState(
    val round: Int,
    val players: Map<String, Combatant>,
    val enemy: EnemyCombatant,
    val telegraph: EnemyTelegraph,
    val lockedActions: Map<String, CombatAction> = emptyMap(),
    val status: CombatStatus = CombatStatus.ACTIVE,
)

data class CombatModifiers(
    val attackBonus: Int = 0,
    val damageReduction: Int = 0,
    val busoshokuBonus: Int = 0,
    val haoshokuBonus: Int = 0,
    val devilFruitBonus: Int = 0,
) {
    init {
        require(attackBonus >= 0) { "Attack bonus cannot be negative" }
        require(damageReduction >= 0) { "Damage reduction cannot be negative" }
        require(busoshokuBonus >= 0 && haoshokuBonus >= 0 && devilFruitBonus >= 0) { "Power bonuses cannot be negative" }
    }
}

data class CombatRoundResult(
    val state: CombatState,
    val enemyDamage: Int,
    val playerDamage: Map<String, Int>,
    val coopCombo: Boolean,
    val log: List<String>,
)

class CombatRuleException(message: String) : RuntimeException(message)

class CombatEngine(private val seed: Long, private val modifiers: Map<String, CombatModifiers> = emptyMap()) {
    fun lockAction(state: CombatState, action: CombatAction): CombatState {
        if (state.status != CombatStatus.ACTIVE) throw CombatRuleException("Combat is not active")
        val fighter = state.players[action.playerId]
            ?: throw CombatRuleException("Unknown player ${action.playerId}")
        if (fighter.hp <= 0) throw CombatRuleException("${action.playerId} is down")
        if (action.playerId in state.lockedActions) {
            throw CombatRuleException("${action.playerId} already locked an action")
        }
        return state.copy(lockedActions = state.lockedActions + (action.playerId to action))
    }

    fun resolveIfReady(state: CombatState): CombatRoundResult? {
        val requiredPlayers = state.players.values.filter { it.hp > 0 }.map { it.id }.toSet()
        if (!state.lockedActions.keys.containsAll(requiredPlayers)) return null
        if (requiredPlayers.isEmpty()) {
            return CombatRoundResult(
                state.copy(status = CombatStatus.DEFEAT),
                enemyDamage = 0,
                playerDamage = state.players.keys.associateWith { 0 },
                coopCombo = false,
                log = listOf("A tripulação foi derrotada."),
            )
        }
        return resolve(state)
    }

    private fun resolve(state: CombatState): CombatRoundResult {
        val random = Random(seed xor (state.round.toLong() * -7046029254386353131L))
        val actions = state.lockedActions
        val p1 = actions["p1"]
        val p2 = actions["p2"]
        val coopCombo = isCoopCombo(p1, p2)
        val log = mutableListOf<String>()

        var enemyDamage = 0
        actions.toSortedMap().forEach { (playerId, action) ->
            val modifier = modifiers[playerId] ?: CombatModifiers()
            val baseDamage = when (action.type) {
                CombatActionType.ATTACK -> 12 + random.nextInt(6)
                CombatActionType.DEFEND -> 0
                CombatActionType.DODGE -> 4 + random.nextInt(3)
                CombatActionType.SETUP -> 7 + random.nextInt(4)
                CombatActionType.FINISHER -> 20 + random.nextInt(7)
                CombatActionType.HAKI_BUSOSHOKU -> 20 + random.nextInt(7) + modifier.busoshokuBonus
                CombatActionType.HAKI_KENBUNSHOKU -> 4 + random.nextInt(3)
                CombatActionType.HAKI_HAOSHOKU -> 18 + random.nextInt(8) + modifier.haoshokuBonus
                CombatActionType.DEVIL_FRUIT -> 16 + random.nextInt(8) + modifier.devilFruitBonus
            }
            val attackBonus = when (action.type) {
                CombatActionType.ATTACK, CombatActionType.SETUP, CombatActionType.FINISHER,
                CombatActionType.HAKI_BUSOSHOKU, CombatActionType.HAKI_HAOSHOKU, CombatActionType.DEVIL_FRUIT -> modifier.attackBonus
                CombatActionType.DEFEND, CombatActionType.DODGE, CombatActionType.HAKI_KENBUNSHOKU -> 0
            }
            val damage = baseDamage + attackBonus
            enemyDamage += damage
            if (damage > 0) log += "$playerId causa $damage de dano."
        }
        if (coopCombo) {
            enemyDamage += 25
            log += "CO-OP COMBO: abertura e finalização sincronizadas! +25 de dano."
        }

        val enemyHpAfter = (state.enemy.hp - enemyDamage).coerceAtLeast(0)
        var updatedPlayers = state.players
        val playerDamage = state.players.keys.associateWith { 0 }.toMutableMap()

        if (enemyHpAfter > 0) {
            val targetId = state.telegraph.targetPlayerId
            val target = updatedPlayers[targetId]
                ?: throw CombatRuleException("Telegraph targets unknown player $targetId")
            if (target.hp > 0) {
                val targetAction = actions[targetId]?.type
                val baseDamage = state.enemy.attackPower + random.nextInt(5)
                val guardedDamage = when {
                    (targetAction == CombatActionType.DODGE || targetAction == CombatActionType.HAKI_KENBUNSHOKU) && state.telegraph.type == EnemyAttackType.HEAVY_STRIKE -> 0
                    targetAction == CombatActionType.DEFEND -> (baseDamage / 3).coerceAtLeast(1)
                    else -> baseDamage
                }
                val damage = (guardedDamage - (modifiers[targetId]?.damageReduction ?: 0)).coerceAtLeast(0)
                playerDamage[targetId] = damage
                updatedPlayers = updatedPlayers + (
                    targetId to target.copy(hp = (target.hp - damage).coerceAtLeast(0))
                )
                if (damage == 0) log += "$targetId evita completamente o golpe telegrafado."
                else log += "${state.enemy.name} atinge $targetId por $damage."
            }
        }

        val status = when {
            enemyHpAfter <= 0 -> CombatStatus.VICTORY
            updatedPlayers.values.all { it.hp <= 0 } -> CombatStatus.DEFEAT
            else -> CombatStatus.ACTIVE
        }
        val nextRound = state.round + 1
        val nextTelegraph = nextTelegraph(nextRound, updatedPlayers)
        val nextState = state.copy(
            round = nextRound,
            players = updatedPlayers,
            enemy = state.enemy.copy(hp = enemyHpAfter),
            telegraph = nextTelegraph,
            lockedActions = emptyMap(),
            status = status,
        )
        return CombatRoundResult(nextState, enemyDamage, playerDamage, coopCombo, log)
    }

    private fun isCoopCombo(p1: CombatAction?, p2: CombatAction?): Boolean {
        if (p1 == null || p2 == null) return false
        fun finisherLike(type: CombatActionType): Boolean = type in setOf(
            CombatActionType.FINISHER,
            CombatActionType.HAKI_BUSOSHOKU,
            CombatActionType.HAKI_HAOSHOKU,
            CombatActionType.DEVIL_FRUIT,
        )
        return (p1.type == CombatActionType.SETUP && finisherLike(p2.type)) ||
            (p2.type == CombatActionType.SETUP && finisherLike(p1.type))
    }

    private fun nextTelegraph(round: Int, players: Map<String, Combatant>): EnemyTelegraph {
        val alive = players.values.filter { it.hp > 0 }.map { it.id }.sorted()
        val target = if (alive.isEmpty()) "p1" else {
            val random = Random(seed xor (round.toLong() * 6364136223846793005L))
            alive[random.nextInt(alive.size)]
        }
        val type = if (round % 3 == 0) EnemyAttackType.SWEEP else EnemyAttackType.HEAVY_STRIKE
        return EnemyTelegraph(type, target)
    }
}
