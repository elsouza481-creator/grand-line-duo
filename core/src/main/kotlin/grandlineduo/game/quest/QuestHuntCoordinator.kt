package grandlineduo.game.quest

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
import grandlineduo.game.combat.CombatEngine
import grandlineduo.game.combat.CombatModifierResolver
import grandlineduo.game.combat.CombatRuleException
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.scenario.ScenarioStage

/** Host-only authority for deterministic combat attached to an active HUNT contract. */
class QuestHuntCoordinator(
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
        requirePlayer(playerId)
        val fingerprint = "quest-hunt-start|$playerId|$questId"
        existing(commandId, fingerprint)?.let { return it }

        val world = hostReplica.state
        val restored = StormglassPersistenceAdapter.decode(world)
        require(world.players["p1"]?.profile != null && world.players["p2"]?.profile != null) {
            "Both characters must be created before starting a hunt"
        }
        require((world.players["p1"]?.hp ?: 0) > 0 && (world.players["p2"]?.hp ?: 0) > 0) {
            "Both players must be alive to start a hunt"
        }
        require(world.activeCombat == null && restored.combat == null) { "Combat is already active" }
        require(world.activeVoyage == null) { "Cannot start hunt during voyage" }
        require(world.activeDuel == null) { "Cannot start hunt during duel" }
        require(world.worldFlags[ACTIVE_QUEST_FLAG] == null) { "Hunt combat is already bound" }
        require(world.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] == null) { "Quest boss combat is already bound" }
        require(world.activeArc == null || world.activeArc.phase == ArcPhase.COMPLETE) {
            "Cannot start hunt before the active arc is complete"
        }
        if (world.activeArc == null) {
            require(restored.scenario.stage == ScenarioStage.COMPLETE) {
                "Cannot start hunt before the current scenario is complete"
            }
        }

        val progress = world.questBoard.active[questId]
            ?: throw IllegalArgumentException("Hunt quest is not active: $questId")
        validateBoundQuest(world, questId, progress)

        val combat = QuestHuntFactory.create(world, progress, campaignSeed)
        val encounter = QuestHuntFactory.encounterIndex(progress)
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
                "meta.questAction" to "START_HUNT",
                "meta.questId" to questId,
                "meta.huntEncounter" to encounter.toString(),
                "meta.huntTarget" to progress.definition.targetId,
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
        requirePlayer(playerId)
        val fingerprint = "quest-hunt-combat|$playerId|${actionType.name}"
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
        requirePlayer(playerId)
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
        require(sourceWorld.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] == null) {
            "Invalid simultaneous HUNT and BOSS combat bindings"
        }
        val questId = sourceWorld.worldFlags[ACTIVE_QUEST_FLAG]
            ?: throw IllegalArgumentException("Active combat is not bound to a hunt")
        val progress = sourceWorld.questBoard.active[questId]
            ?: throw IllegalArgumentException("Bound hunt quest is not active: $questId")
        validateBoundQuest(sourceWorld, questId, progress)
        val current = sourceWorld.activeCombat
            ?: throw IllegalArgumentException("No active hunt combat")
        require(current.status == CombatStatus.ACTIVE) { "Hunt combat is not active" }

        val encounter = QuestHuntFactory.encounterIndex(progress)
        val engine = CombatEngine(
            QuestHuntFactory.combatSeed(progress, campaignSeed),
            CombatModifierResolver.forWorld(sourceWorld),
        )
        val locked = try {
            engine.lockAction(current, CombatAction(playerId, actionType))
        } catch (e: CombatRuleException) {
            throw IllegalArgumentException(e.message ?: "Invalid hunt combat action")
        }
        val result = engine.resolveIfReady(locked)
        val metadata = baseMetadata.toMutableMap().apply {
            put("meta.questId", questId)
            put("meta.huntQuestId", questId)
            put("meta.huntEncounter", encounter.toString())
        }

        val nextWorld = if (result == null) {
            metadata["meta.hunt"] = "ACTION_LOCKED"
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
                    metadata["meta.hunt"] = "VICTORY"
                    val amount = QuestHuntFactory.progressPerVictory(progress.definition.rarity)
                    val cleared = sourceWorld.copy(
                        players = players,
                        activeCombat = null,
                        worldFlags = sourceWorld.worldFlags - ACTIVE_QUEST_FLAG,
                    )
                    val progressed = QuestObjectiveRouter.apply(
                        cleared,
                        QuestObjectiveEvent(
                            type = QuestObjectiveEventType.ENEMY_DEFEATED,
                            targetId = progress.definition.targetId,
                            islandId = progress.definition.islandId,
                            amount = amount,
                            sourceQuestId = questId,
                        ),
                    )
                    val newProgress = progressed.questBoard.active[questId]?.progress
                        ?: throw IllegalStateException("Hunt objective disappeared after victory")
                    metadata["meta.questObjective"] = QuestObjectiveEventType.ENEMY_DEFEATED.name
                    metadata["meta.questObjectiveSourceQuest"] = questId
                    metadata["meta.questObjectiveTarget"] = progress.definition.targetId
                    metadata["meta.questObjectiveAmount"] = amount.toString()
                    metadata["meta.questProgress"] = newProgress.toString()
                    progressed
                }
                CombatStatus.DEFEAT -> {
                    metadata["meta.hunt"] = "DEFEAT"
                    metadata["meta.questFailure"] = "HUNT_DEFEAT"
                    val defeated = sourceWorld.copy(
                        players = players,
                        activeCombat = result.state,
                        worldFlags = sourceWorld.worldFlags - ACTIVE_QUEST_FLAG,
                    )
                    QuestEngine.fail(defeated, questId, "hunt defeat")
                }
                CombatStatus.ACTIVE -> {
                    metadata["meta.hunt"] = "ROUND_RESOLVED"
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

    private fun validateBoundQuest(world: WorldState, questId: String, progress: QuestProgress) {
        require(progress.definition.questId == questId) { "Bound hunt quest id mismatch" }
        require(progress.definition.type == QuestType.HUNT) { "Bound quest is not a hunt contract" }
        require(progress.status == QuestStatus.ACTIVE) { "Bound hunt quest is not active" }
        require(progress.definition.islandId == world.islandId) { "Bound hunt quest is not on current island" }
    }

    private fun requirePlayer(playerId: String) {
        require(playerId == "p1" || playerId == "p2") { "Unknown player $playerId" }
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
        const val ACTIVE_QUEST_FLAG = "quest.hunt.active"
    }
}
