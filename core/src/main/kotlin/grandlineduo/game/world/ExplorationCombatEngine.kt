package grandlineduo.game.world

import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph

/**
 * Exploration-only combat lifecycle. Combat rules themselves remain in CombatEngine.
 * Encounter identity is stored in world flags so the existing snapshot/hash/LAN stack persists it.
 */
object ExplorationCombatEngine {
    fun isDefeated(world: WorldState, enemyId: String): Boolean =
        world.worldFlags[defeatedKey(world.islandId, enemyId)] == "true"

    fun isActive(world: WorldState): Boolean {
        val enemyId = world.worldFlags[activeKey(world.islandId)] ?: return false
        return world.activeCombat?.enemy?.id == enemyId
    }

    fun activeEnemy(world: WorldState): ExplorationEnemy {
        val enemyId = world.worldFlags[activeKey(world.islandId)]
            ?: throw IllegalArgumentException("No active exploration encounter")
        return ExplorationEngine.mapFor(world.campaignId, world.islandId)
            .enemies.values
            .firstOrNull { it.id == enemyId }
            ?: throw IllegalArgumentException("Unknown active exploration encounter $enemyId")
    }

    fun startIfEncountered(world: WorldState, playerId: String): WorldState {
        require(playerId in world.players) { "Unknown player $playerId" }
        if (world.activeCombat != null) return world
        val enemy = ExplorationEngine.enemyAt(world, playerId) ?: return world
        if (isDefeated(world, enemy.id)) return world

        val fighters = world.players.mapValues { (id, player) ->
            Combatant(id, player.name, player.hp, player.maxHp)
        }
        val alive = fighters.values.filter { it.hp > 0 }.map { it.id }.sorted()
        val seed = combatSeed(world.campaignId, world.islandId, enemy.id)
        val target = if (alive.isEmpty()) {
            "p1"
        } else {
            val index = ((seed xor (seed ushr 32)).toInt() and Int.MAX_VALUE) % alive.size
            alive[index]
        }
        val combat = CombatState(
            round = 1,
            players = fighters,
            enemy = EnemyCombatant(
                id = enemy.id,
                name = enemy.name,
                hp = enemy.maxHp,
                maxHp = enemy.maxHp,
                attackPower = enemy.attackPower,
            ),
            telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, target),
        )
        return world.copy(
            activeCombat = combat,
            worldFlags = world.worldFlags + (activeKey(world.islandId) to enemy.id),
        )
    }

    fun combatSeed(world: WorldState): Long {
        val enemy = activeEnemy(world)
        return combatSeed(world.campaignId, world.islandId, enemy.id)
    }

    fun completeVictory(world: WorldState): WorldState {
        require(isActive(world)) { "No active exploration encounter" }
        val combat = world.activeCombat ?: throw IllegalArgumentException("No active combat")
        require(combat.status == CombatStatus.VICTORY) { "Exploration combat is not won" }
        val enemy = activeEnemy(world)
        require(combat.enemy.id == enemy.id) { "Active encounter and combat enemy do not match" }

        val syncedPlayers = world.players.mapValues { (id, player) ->
            combat.players[id]?.let { fighter ->
                player.copy(hp = fighter.hp, maxHp = fighter.maxHp)
            } ?: player
        }
        val clearedFlags = world.worldFlags - activeKey(world.islandId)
        if (isDefeated(world, enemy.id)) {
            return world.copy(players = syncedPlayers, activeCombat = null, worldFlags = clearedFlags)
        }
        return world.copy(
            players = syncedPlayers,
            activeCombat = null,
            partyBerries = world.partyBerries + enemy.rewardBerries,
            worldFlags = clearedFlags + (defeatedKey(world.islandId, enemy.id) to "true"),
        )
    }

    private fun activeKey(islandId: String): String = "explore.$islandId.combat.enemy"

    private fun defeatedKey(islandId: String, enemyId: String): String =
        "explore.$islandId.enemy.$enemyId.defeated"

    private fun combatSeed(campaignId: String, islandId: String, enemyId: String): Long {
        var hash = 0xCBF29CE484222325UL.toLong()
        "$campaignId|$islandId|$enemyId|free-roam-combat-v1".forEach { ch ->
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001B3L
        }
        return hash
    }
}
