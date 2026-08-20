package grandlineduo.game.arc

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.combat.*

/** Host-only authority for combat attached to a narrative arc. */
class ArcCombatCoordinator(
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
        require(playerId == "p1" || playerId == "p2") { "Unknown player $playerId" }
        val fingerprint = "arc-combat|$playerId|${actionType.name}"
        hostReplica.events.firstOrNull { it.commandId == commandId }?.let { existing ->
            require(existing.commandFingerprint == fingerprint) { "Command ID collision" }
            persist(existing)
            return existing
        }

        val arc = hostReplica.state.activeArc ?: throw IllegalArgumentException("No active arc")
        val current = hostReplica.state.activeCombat ?: throw IllegalArgumentException("No active arc combat")
        val engine = CombatEngine(ArcBossFactory.combatSeed(arc), CombatModifierResolver.forWorld(hostReplica.state))
        val locked = try {
            engine.lockAction(current, CombatAction(playerId, actionType))
        } catch (e: CombatRuleException) {
            throw IllegalArgumentException(e.message ?: "Invalid combat action")
        }
        val result = engine.resolveIfReady(locked)

        val nextCombat: CombatState?
        var nextFlags = hostReplica.state.worldFlags
        var nextPlayers = hostReplica.state.players
        val metadata = mutableMapOf<String, String>()

        if (result == null) {
            nextCombat = locked
            metadata["meta.roundResolved"] = "false"
            metadata["meta.coopCombo"] = "false"
            metadata["meta.combatStatus"] = locked.status.name
        } else {
            nextPlayers = nextPlayers.mapValues { (id, player) ->
                result.state.players[id]?.let { fighter ->
                    player.copy(hp = fighter.hp, maxHp = fighter.maxHp)
                } ?: player
            }
            metadata["meta.roundResolved"] = "true"
            metadata["meta.coopCombo"] = result.coopCombo.toString()
            metadata["meta.combatStatus"] = result.state.status.name
            metadata["meta.enemyDamage"] = result.enemyDamage.toString()
            metadata["meta.combatLog"] = result.log.joinToString("\n")
            when (result.state.status) {
                CombatStatus.VICTORY -> {
                    nextCombat = null
                    nextFlags = nextFlags + ("ARC_BOSS_DEFEATED:${arc.arcId}" to "true")
                }
                CombatStatus.DEFEAT -> {
                    nextCombat = result.state
                    nextFlags = nextFlags + ("ARC_PARTY_DEFEATED:${arc.arcId}" to "true")
                }
                CombatStatus.ACTIVE -> nextCombat = result.state
            }
        }

        val nextWorld = hostReplica.state.copy(
            players = nextPlayers,
            activeCombat = nextCombat,
            worldFlags = nextFlags,
        )
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
