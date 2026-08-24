package grandlineduo.game.quest

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.combat.CombatAction
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatEngine
import grandlineduo.game.combat.CombatModifierResolver
import grandlineduo.game.combat.CombatRuleException
import grandlineduo.game.combat.CombatStatus

/** Host-only authority for combat attached to a sandbox BOSS contract. */
class QuestBossCoordinator(
    private val hostReplica: HostReplica,
    private val campaignSeed: Long,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun start(
        commandId: String,
        playerId: String,
        questId: String,
        hostTimestamp: Long,
    ): CampaignEvent {
        require(playerId == "p1" || playerId == "p2") { "Unknown player $playerId" }
        val fingerprint = "quest-boss-start|$playerId|$questId"
        existing(commandId, fingerprint)?.let { return it }

        val world = hostReplica.state
        require(world.activeCombat == null) { "Combat is already active" }
        require(world.activeVoyage == null) { "Cannot start quest boss during voyage" }
        require(world.worldFlags[ACTIVE_QUEST_FLAG] == null) { "Quest boss combat is already bound" }
        val progress = world.questBoard.active[questId]
            ?: throw IllegalArgumentException("Boss quest is not active: $questId")
        require(progress.status == QuestStatus.ACTIVE) { "Boss quest is not active" }
        require(progress.definition.type == QuestType.BOSS) { "Quest is not a boss contract" }
        require(progress.definition.islandId == world.islandId) { "Boss quest is not on current island" }

        val combat = QuestBossFactory.create(world, progress.definition, campaignSeed)
        val nextWorld = world.copy(
            activeCombat = combat,
            worldFlags = world.worldFlags + (ACTIVE_QUEST_FLAG to questId),
        )
        return commit(
            commandId = commandId,
            playerId = playerId,
            fingerprint = fingerprint,
            nextWorld = nextWorld,
            metadata = mapOf(
                "meta.questBoss" to "STARTED",
                "meta.questId" to questId,
                "meta.enemyId" to combat.enemy.id,
            ),
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
        require(playerId == "p1" || playerId == "p2") { "Unknown player $playerId" }
        val fingerprint = "quest-boss-combat|$playerId|${actionType.name}"
        existing(commandId, fingerprint)?.let { return it }
        return resolveAction(
            commandId = commandId,
            playerId = playerId,
            actionType = actionType,
            sourceWorld = hostReplica.state,
            fingerprint = fingerprint,
            baseMetadata = emptyMap(),
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
        metadata: Map<String, String>,
        hostTimestamp: Long,
    ): CampaignEvent {
        require(playerId == "p1" || playerId == "p2") { "Unknown player $playerId" }
        existing(commandId, sourceFingerprint)?.let { return it }
        require(preparedWorld.campaignId == hostReplica.state.campaignId) { "Prepared world campaign mismatch" }
        return resolveAction(
            commandId = commandId,
            playerId = playerId,
            actionType = actionType,
            sourceWorld = preparedWorld,
            fingerprint = sourceFingerprint,
            baseMetadata = metadata,
            hostTimestamp = hostTimestamp,
        )
    }

    private fun resolveAction(
        commandId: String,
        playerId: String,
        actionType: CombatActionType,
        sourceWorld: WorldState,
        fingerprint: String,
        baseMetadata: Map<String, String>,
        hostTimestamp: Long,
    ): CampaignEvent {
        val questId = sourceWorld.worldFlags[ACTIVE_QUEST_FLAG]
            ?: throw IllegalArgumentException("Active combat is not bound to a quest boss")
        val progress = sourceWorld.questBoard.active[questId]
            ?: throw IllegalArgumentException("Bound boss quest is not active: $questId")
        require(progress.definition.type == QuestType.BOSS) { "Bound quest is not a boss contract" }
        require(progress.status == QuestStatus.ACTIVE) { "Bound boss quest is not active" }
        require(progress.definition.islandId == sourceWorld.islandId) { "Bound boss quest is not on current island" }
        val current = sourceWorld.activeCombat
            ?: throw IllegalArgumentException("No active quest boss combat")

        val engine = CombatEngine(
            QuestBossFactory.combatSeed(progress.definition, campaignSeed),
            CombatModifierResolver.forWorld(sourceWorld),
        )
        val locked = try {
            engine.lockAction(current, CombatAction(playerId, actionType))
        } catch (e: CombatRuleException) {
            throw IllegalArgumentException(e.message ?: "Invalid quest boss combat action")
        }
        val result = engine.resolveIfReady(locked)
        val metadata = baseMetadata.toMutableMap().apply {
            put("meta.questId", questId)
        }

        val nextWorld = if (result == null) {
            metadata["meta.questBoss"] = "ACTION_LOCKED"
            metadata["meta.roundResolved"] = "false"
            metadata["meta.coopCombo"] = "false"
            metadata["meta.combatStatus"] = locked.status.name
            sourceWorld.copy(activeCombat = locked)
        } else {
            val players = sourceWorld.players.mapValues { (id, player) ->
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
                    metadata["meta.questBoss"] = "VICTORY"
                    val cleared = sourceWorld.copy(
                        players = players,
                        activeCombat = null,
                        worldFlags = sourceWorld.worldFlags - ACTIVE_QUEST_FLAG,
                    )
                    QuestEngine.completeBossObjective(cleared, questId)
                }
                CombatStatus.DEFEAT -> {
                    metadata["meta.questBoss"] = "DEFEAT"
                    metadata["meta.questFailure"] = "BOSS_DEFEAT"
                    val defeated = sourceWorld.copy(
                        players = players,
                        activeCombat = result.state,
                        worldFlags = sourceWorld.worldFlags - ACTIVE_QUEST_FLAG,
                    )
                    QuestEngine.fail(defeated, questId, "boss defeat")
                }
                CombatStatus.ACTIVE -> {
                    metadata["meta.questBoss"] = "ROUND_RESOLVED"
                    sourceWorld.copy(players = players, activeCombat = result.state)
                }
            }
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

    companion object {
        const val ACTIVE_QUEST_FLAG = "quest.boss.active"
    }
}
