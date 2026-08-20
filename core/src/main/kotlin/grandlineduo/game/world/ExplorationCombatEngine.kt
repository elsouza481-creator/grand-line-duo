package grandlineduo.game.world

import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine
import grandlineduo.game.character.ClassMasteryEngine
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph

/**
 * Exploration-only combat lifecycle. Combat rules themselves remain in CombatEngine.
 * Encounter identity and respawn cadence are stored in world flags so the existing
 * snapshot/hash/LAN stack persists them without a new wire or snapshot schema.
 */
object ExplorationCombatEngine {
    fun isDefeated(world: WorldState, enemyId: String): Boolean {
        if (world.worldFlags[defeatedKey(world.islandId, enemyId)] != "true") return false
        val respawnAt = world.worldFlags[respawnAtKey(world.islandId, enemyId)]?.toLongOrNull()
            ?: return true
        return ExplorationEngine.explorationSteps(world) < respawnAt
    }

    fun stepsUntilRespawn(world: WorldState, enemyId: String): Int {
        if (world.worldFlags[defeatedKey(world.islandId, enemyId)] != "true") return 0
        val respawnAt = world.worldFlags[respawnAtKey(world.islandId, enemyId)]?.toLongOrNull()
            ?: return Int.MAX_VALUE
        val remaining = (respawnAt - ExplorationEngine.explorationSteps(world)).coerceAtLeast(0L)
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

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
            telegraph = EnemyTelegraph(enemy.initialAttackType, target),
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

        val currentSteps = ExplorationEngine.explorationSteps(world)
        val respawnAt = if (currentSteps > Long.MAX_VALUE - enemy.respawnSteps.toLong()) {
            Long.MAX_VALUE
        } else {
            currentSteps + enemy.respawnSteps.toLong()
        }
        var rewarded = world.copy(
            players = syncedPlayers,
            activeCombat = null,
            partyBerries = world.partyBerries + enemy.rewardBerries,
            worldFlags = clearedFlags + mapOf(
                defeatedKey(world.islandId, enemy.id) to "true",
                respawnAtKey(world.islandId, enemy.id) to respawnAt.toString(),
            ),
        )
        combat.players.values
            .filter { it.hp > 0 && it.id in rewarded.players }
            .sortedBy { it.id }
            .forEach { fighter ->
                val player = rewarded.players.getValue(fighter.id)
                val profile = player.profile
                val mastery = profile?.classMastery
                if (profile != null && mastery != null) {
                    val primary = mastery.primaryClass
                    val progressed = ClassMasteryEngine.train(
                        mastery,
                        primary,
                        enemy.rewardMasteryExperience.toLong(),
                    )
                    rewarded = rewarded.copy(
                        players = rewarded.players + (
                            fighter.id to player.copy(profile = profile.copy(classMastery = progressed))
                        ),
                    )
                }
                rewarded = InventoryEngine.grant(
                    rewarded,
                    fighter.id,
                    enemy.rewardItemId,
                    enemy.rewardItemAmount,
                )
            }
        return rewarded
    }

    private fun activeKey(islandId: String): String = "explore.$islandId.combat.enemy"

    private fun defeatedKey(islandId: String, enemyId: String): String =
        "explore.$islandId.enemy.$enemyId.defeated"

    private fun respawnAtKey(islandId: String, enemyId: String): String =
        "explore.$islandId.enemy.$enemyId.respawnAtStep"

    private fun combatSeed(campaignId: String, islandId: String, enemyId: String): Long {
        var hash = 0xCBF29CE484222325UL.toLong()
        "$campaignId|$islandId|$enemyId|free-roam-combat-v1".forEach { ch ->
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001B3L
        }
        return hash
    }
}
