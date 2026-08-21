package grandlineduo.game.world

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.combat.CombatAction
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatEngine
import grandlineduo.game.combat.CombatModifierResolver
import grandlineduo.game.combat.CombatRuleException
import grandlineduo.game.combat.CombatStatus

/** Host authority for physical exploration encounters that are not narrative arc bosses. */
class ExplorationCombatCoordinator(
    private val hostReplica: HostReplica,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun submitAction(
        commandId: String,
        playerId: String,
        actionType: CombatActionType,
        hostTimestamp: Long,
    ): CampaignEvent {
        require(playerId in setOf("p1", "p2", "p3", "p4")) { "Unknown player $playerId" }
        // Must match GameplayWireCommand.CombatAction.fingerprint() so retries remain idempotent
        // through the handler's top-level collision guard.
        val fingerprint = "combat-action|$playerId|${actionType.name}"
        hostReplica.events.firstOrNull { it.commandId == commandId }?.let { existing ->
            require(existing.commandFingerprint == fingerprint) { "Command ID collision" }
            persist(existing)
            return existing
        }

        val before = hostReplica.state
        require(ExplorationCombatEngine.isActive(before)) { "No active exploration combat" }
        val current = before.activeCombat ?: throw IllegalArgumentException("No active combat")
        val engine = CombatEngine(
            ExplorationCombatEngine.combatSeed(before),
            CombatModifierResolver.forWorld(before),
        )
        val locked = try {
            engine.lockAction(current, CombatAction(playerId, actionType))
        } catch (e: CombatRuleException) {
            throw IllegalArgumentException(e.message ?: "Invalid combat action")
        }
        val resolution = engine.resolveIfReady(locked)
        val metadata = mutableMapOf<String, String>()

        val nextWorld = if (resolution == null) {
            metadata["meta.roundResolved"] = "false"
            metadata["meta.coopCombo"] = "false"
            metadata["meta.combatStatus"] = locked.status.name
            before.copy(activeCombat = locked)
        } else {
            metadata["meta.roundResolved"] = "true"
            metadata["meta.coopCombo"] = resolution.coopCombo.toString()
            metadata["meta.combatStatus"] = resolution.state.status.name
            metadata["meta.enemyDamage"] = resolution.enemyDamage.toString()
            metadata["meta.combatLog"] = resolution.log.joinToString("\n")

            val syncedPlayers = before.players.mapValues { (id, player) ->
                resolution.state.players[id]?.let { fighter ->
                    player.copy(hp = fighter.hp, maxHp = fighter.maxHp)
                } ?: player
            }
            val resolvedWorld = before.copy(
                players = syncedPlayers,
                activeCombat = resolution.state,
            )
            when (resolution.state.status) {
                CombatStatus.VICTORY -> ExplorationCombatEngine.completeVictory(resolvedWorld)
                CombatStatus.ACTIVE, CombatStatus.DEFEAT -> resolvedWorld
            }
        }

        val event = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = commandId,
                actorId = playerId,
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = metadata,
            ),
            hostTimestamp,
        ).event
        persist(event)
        return event
    }

    private fun persist(event: CampaignEvent) {
        if (durableStore != null) durableStore.commit(event, hostReplica.state)
        else snapshotStore?.save(hostReplica.state)
    }
}
