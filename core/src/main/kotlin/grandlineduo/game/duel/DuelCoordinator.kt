package grandlineduo.game.duel

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.combat.CombatAction
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatModifierResolver
import grandlineduo.game.scenario.ScenarioStage
import java.security.MessageDigest

/** Host-only authority for consensual, non-lethal PvP between the two human players. */
class DuelCoordinator(
    private val hostReplica: HostReplica,
    private val campaignSeed: Long,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun challenge(commandId: String, playerId: String, hostTimestamp: Long): CampaignEvent {
        requirePlayer(playerId)
        val fingerprint = "duel-challenge|$playerId"
        existing(commandId, fingerprint)?.let { return it }

        val world = hostReplica.state
        require(world.worldFlags["campaign.mode"] == "HOST_COOP") { "PvP duel requires a two-human co-op campaign" }
        require(world.activeDuel == null) { "A duel already exists" }
        require(world.activeCombat == null) { "Cannot challenge during PvE combat" }
        require(world.activeVoyage == null) { "Cannot challenge during a voyage incident" }
        val restored = StormglassPersistenceAdapter.decode(world)
        require(restored.combat == null) { "Cannot challenge during scenario combat" }
        require(world.activeArc == null || world.activeArc.phase == ArcPhase.COMPLETE) {
            "Cannot challenge during an active narrative arc"
        }
        require(restored.scenario.stage == ScenarioStage.COMPLETE || world.activeArc?.phase == ArcPhase.COMPLETE) {
            "PvP duel is available only from the hub"
        }

        val p1 = world.players["p1"] ?: throw IllegalArgumentException("Missing player p1")
        val p2 = world.players["p2"] ?: throw IllegalArgumentException("Missing player p2")
        require(p1.profile != null && p2.profile != null) { "Both characters must be created before a duel" }
        require(p1.hp > 0 && p2.hp > 0) { "Both players must be conscious before a duel" }

        val challengedId = if (playerId == "p1") "p2" else "p1"
        val duel = DuelState(
            duelId = duelId(world, commandId, playerId),
            challengerId = playerId,
            challengedId = challengedId,
            phase = DuelPhase.PENDING,
        )
        return commit(
            commandId = commandId,
            playerId = playerId,
            fingerprint = fingerprint,
            nextWorld = world.copy(activeDuel = duel),
            metadata = lifecycleMetadata(duel),
            hostTimestamp = hostTimestamp,
        )
    }

    @Synchronized
    fun accept(commandId: String, playerId: String, hostTimestamp: Long): CampaignEvent {
        requirePlayer(playerId)
        val fingerprint = "duel-accept|$playerId"
        existing(commandId, fingerprint)?.let { return it }

        val world = hostReplica.state
        val pending = world.activeDuel ?: throw IllegalArgumentException("No duel challenge is pending")
        require(pending.phase == DuelPhase.PENDING) { "Duel challenge is not pending" }
        require(playerId == pending.challengedId) { "Only the challenged player may accept" }

        val fighters = listOf("p1", "p2").associateWith { id ->
            val player = world.players[id] ?: throw IllegalArgumentException("Missing player $id")
            require(player.profile != null) { "Character not created for $id" }
            require(player.hp > 0) { "$id is not conscious" }
            DuelFighter(id = id, name = player.name, hp = player.hp, maxHp = player.maxHp)
        }
        val active = pending.copy(
            phase = DuelPhase.ACTIVE,
            round = 1,
            fighters = fighters,
            lockedActions = emptyMap(),
            setupReady = emptySet(),
            winnerId = null,
            loserId = null,
            finishReason = null,
        )
        return commit(
            commandId = commandId,
            playerId = playerId,
            fingerprint = fingerprint,
            nextWorld = world.copy(activeDuel = active),
            metadata = lifecycleMetadata(active),
            hostTimestamp = hostTimestamp,
        )
    }

    @Synchronized
    fun decline(commandId: String, playerId: String, hostTimestamp: Long): CampaignEvent {
        requirePlayer(playerId)
        val fingerprint = "duel-decline|$playerId"
        existing(commandId, fingerprint)?.let { return it }

        val world = hostReplica.state
        val pending = world.activeDuel ?: throw IllegalArgumentException("No duel challenge is pending")
        require(pending.phase == DuelPhase.PENDING) { "Duel challenge is not pending" }
        require(playerId == pending.challengedId) { "Only the challenged player may decline" }
        return commit(
            commandId = commandId,
            playerId = playerId,
            fingerprint = fingerprint,
            nextWorld = world.copy(activeDuel = null),
            metadata = lifecycleMetadata(pending) + ("meta.duelPhase" to "DECLINED"),
            hostTimestamp = hostTimestamp,
        )
    }

    @Synchronized
    fun close(commandId: String, playerId: String, hostTimestamp: Long): CampaignEvent {
        requirePlayer(playerId)
        val fingerprint = "duel-close|$playerId"
        existing(commandId, fingerprint)?.let { return it }

        val world = hostReplica.state
        val duel = world.activeDuel ?: throw IllegalArgumentException("No duel exists")
        require(duel.phase == DuelPhase.FINISHED) { "Duel can only be closed after it finishes" }
        require(playerId == duel.challengerId || playerId == duel.challengedId) { "Player is not a duel participant" }
        return commit(
            commandId = commandId,
            playerId = playerId,
            fingerprint = fingerprint,
            nextWorld = world.copy(activeDuel = null),
            metadata = lifecycleMetadata(duel) + ("meta.duelPhase" to "CLOSED"),
            hostTimestamp = hostTimestamp,
        )
    }

    @Synchronized
    fun submitAction(
        commandId: String,
        playerId: String,
        actionType: CombatActionType,
        hostTimestamp: Long,
    ): CampaignEvent {
        requirePlayer(playerId)
        require(actionType in BASIC_ACTIONS) { "Power techniques require a power action" }
        val fingerprint = "duel-combat|$playerId|${actionType.name}"
        existing(commandId, fingerprint)?.let { return it }
        return resolveAction(
            commandId = commandId,
            playerId = playerId,
            actionType = actionType,
            sourceWorld = hostReplica.state,
            fingerprint = fingerprint,
            baseMetadata = mutableMapOf(),
            hostTimestamp = hostTimestamp,
        )
    }

    @Synchronized
    fun submitPreparedAction(
        commandId: String,
        playerId: String,
        actionType: CombatActionType,
        preparedWorld: WorldState,
        sourceFingerprint: String,
        metadata: MutableMap<String, String>,
        hostTimestamp: Long,
    ): CampaignEvent {
        requirePlayer(playerId)
        require(actionType in POWER_ACTIONS) { "Prepared duel action must be a power technique" }
        existing(commandId, sourceFingerprint)?.let { return it }
        val authoritative = hostReplica.state
        require(preparedWorld.campaignId == authoritative.campaignId) { "Prepared world campaign mismatch" }
        val preparedDuel = preparedWorld.activeDuel ?: throw IllegalArgumentException("Prepared world has no active duel")
        val authoritativeDuel = authoritative.activeDuel ?: throw IllegalArgumentException("No active duel")
        require(preparedDuel == authoritativeDuel) { "Prepared duel state is stale" }
        return resolveAction(
            commandId = commandId,
            playerId = playerId,
            actionType = actionType,
            sourceWorld = preparedWorld,
            fingerprint = sourceFingerprint,
            baseMetadata = metadata.toMutableMap(),
            hostTimestamp = hostTimestamp,
        )
    }

    private fun resolveAction(
        commandId: String,
        playerId: String,
        actionType: CombatActionType,
        sourceWorld: WorldState,
        fingerprint: String,
        baseMetadata: MutableMap<String, String>,
        hostTimestamp: Long,
    ): CampaignEvent {
        val duel = sourceWorld.activeDuel ?: throw IllegalArgumentException("No active duel")
        require(duel.phase == DuelPhase.ACTIVE) { "Duel is not active" }
        require(playerId == duel.challengerId || playerId == duel.challengedId) { "Player is not a duel participant" }
        require(sourceWorld.activeCombat == null) { "PvE combat cannot overlap a duel" }
        require(StormglassPersistenceAdapter.decode(sourceWorld).combat == null) { "Scenario combat cannot overlap a duel" }

        val engine = DuelEngine(
            campaignSeed xor duel.duelId.hashCode().toLong(),
            CombatModifierResolver.forWorld(sourceWorld),
        )
        val locked = try {
            engine.lockAction(duel, CombatAction(playerId, actionType))
        } catch (e: DuelRuleException) {
            throw IllegalArgumentException(e.message ?: "Invalid duel action")
        }
        val result = try {
            engine.resolveIfReady(locked)
        } catch (e: DuelRuleException) {
            throw IllegalArgumentException(e.message ?: "Invalid duel round")
        }

        val metadata = baseMetadata.apply {
            put("meta.duelId", duel.duelId)
        }
        val nextWorld = if (result == null) {
            metadata["meta.duelPhase"] = locked.phase.name
            metadata["meta.duelRound"] = locked.round.toString()
            metadata["meta.duelResolved"] = "false"
            sourceWorld.copy(activeDuel = locked)
        } else {
            val players = sourceWorld.players.mapValues { (id, player) ->
                result.state.fighters[id]?.let { fighter ->
                    player.copy(hp = fighter.hp, maxHp = fighter.maxHp)
                } ?: player
            }
            metadata["meta.duelPhase"] = result.state.phase.name
            metadata["meta.duelRound"] = result.state.round.toString()
            metadata["meta.duelResolved"] = "true"
            metadata["meta.duelLog"] = result.log.joinToString("\n")
            result.state.finishReason?.let { metadata["meta.duelFinishReason"] = it.name }
            sourceWorld.copy(players = players, activeDuel = result.state)
        }

        return commit(
            commandId = commandId,
            playerId = playerId,
            fingerprint = fingerprint,
            nextWorld = nextWorld,
            metadata = metadata,
            hostTimestamp = hostTimestamp,
        )
    }

    private fun lifecycleMetadata(duel: DuelState): Map<String, String> = mapOf(
        "meta.duelId" to duel.duelId,
        "meta.duelPhase" to duel.phase.name,
    )

    private fun existing(commandId: String, fingerprint: String): CampaignEvent? {
        val event = hostReplica.events.firstOrNull { it.commandId == commandId } ?: return null
        require(event.commandFingerprint == fingerprint) { "Command ID collision" }
        persist(event)
        return event
    }

    private fun commit(
        commandId: String,
        playerId: String,
        fingerprint: String,
        nextWorld: WorldState,
        metadata: Map<String, String>,
        hostTimestamp: Long,
    ): CampaignEvent {
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

    private fun requirePlayer(playerId: String) {
        require(playerId == "p1" || playerId == "p2") { "Unknown player $playerId" }
    }

    private fun duelId(world: WorldState, commandId: String, playerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${world.campaignId}|$commandId|$playerId".toByteArray(Charsets.UTF_8))
        val stable = digest.take(12).joinToString("") { "%02x".format(it) }
        return "duel-$stable"
    }

    private companion object {
        val BASIC_ACTIONS = setOf(
            CombatActionType.ATTACK,
            CombatActionType.DEFEND,
            CombatActionType.DODGE,
            CombatActionType.SETUP,
            CombatActionType.FINISHER,
        )
        val POWER_ACTIONS = setOf(
            CombatActionType.HAKI_BUSOSHOKU,
            CombatActionType.HAKI_KENBUNSHOKU,
            CombatActionType.HAKI_HAOSHOKU,
            CombatActionType.DEVIL_FRUIT,
        )
    }
}
