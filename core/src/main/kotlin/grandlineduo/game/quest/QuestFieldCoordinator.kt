package grandlineduo.game.quest

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.scenario.ScenarioStage

/** Host-only authority for deterministic EXPLORE and COLLECT field attempts. */
class QuestFieldCoordinator(
    private val hostReplica: HostReplica,
    private val campaignSeed: Long,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun attempt(
        commandId: String,
        playerId: String,
        questId: String,
        actionType: QuestFieldActionType,
        hostTimestamp: Long,
    ): CampaignEvent {
        requirePlayer(playerId)
        val fingerprint = "quest-field|$playerId|${actionType.name}|$questId"
        existing(commandId, fingerprint)?.let { return it }

        val world = hostReplica.state
        val restored = StormglassPersistenceAdapter.decode(world)
        require(world.players["p1"]?.profile != null && world.players["p2"]?.profile != null) {
            "Both characters must be created before field actions"
        }
        require((world.players["p1"]?.hp ?: 0) > 0 && (world.players["p2"]?.hp ?: 0) > 0) {
            "Both players must be alive for field actions"
        }
        require((world.players[playerId]?.energy ?: 0) >= 1) { "Field action requires 1 PE" }
        require(world.activeCombat == null && restored.combat == null) { "Field action unavailable during combat" }
        require(world.activeVoyage == null) { "Field action unavailable during voyage" }
        require(world.activeDuel == null) { "Field action unavailable during duel" }
        require(world.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG] == null) {
            "Field action unavailable during hunt binding"
        }
        require(world.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] == null) {
            "Field action unavailable during boss binding"
        }
        require(world.activeArc == null || world.activeArc.phase == ArcPhase.COMPLETE) {
            "Field action requires completed arc"
        }
        if (world.activeArc == null) {
            require(restored.scenario.stage == ScenarioStage.COMPLETE) { "Field action requires completed scenario" }
        }

        val progress = world.questBoard.active[questId]
            ?: throw IllegalArgumentException("Field quest is not active: $questId")
        require(progress.status == QuestStatus.ACTIVE) { "Field quest is not active" }
        require(progress.definition.islandId == world.islandId) { "Field quest is not on the current island" }
        val expectedType = when (actionType) {
            QuestFieldActionType.EXPLORE_SITE -> QuestType.EXPLORE
            QuestFieldActionType.SEARCH_SUPPLIES -> QuestType.COLLECT
        }
        require(progress.definition.type == expectedType) {
            "${actionType.name} does not match ${progress.definition.type.name} contract"
        }

        val ordinal = QuestFieldState.attemptCount(world, questId) + 1
        val resolved = QuestFieldResolver.resolve(
            world = world,
            progress = progress,
            actorId = playerId,
            actionType = actionType,
            attemptOrdinal = ordinal,
            campaignSeed = campaignSeed,
        )
        val actor = world.players.getValue(playerId)
        var next = world.copy(
            players = world.players + (playerId to actor.copy(energy = actor.energy - 1)),
        )
        next = QuestFieldState.writeAttemptResult(next, questId, resolved)

        val metadata = mutableMapOf(
            "meta.questFieldAction" to actionType.name,
            "meta.questId" to questId,
            "meta.questFieldAttempt" to ordinal.toString(),
            "meta.questFieldActor" to playerId,
            "meta.questFieldCheck" to resolved.checkId,
            "meta.questFieldRoll" to resolved.roll.toString(),
            "meta.questFieldModifier" to resolved.modifier.toString(),
            "meta.questFieldTotal" to resolved.total.toString(),
            "meta.questFieldDc" to resolved.difficultyClass.toString(),
            "meta.questFieldSuccess" to resolved.success.toString(),
            "meta.questFieldEnergySpent" to "1",
        )

        if (resolved.success) {
            next = QuestObjectiveRouter.apply(
                next,
                QuestObjectiveEvent(
                    type = resolved.objectiveEventType,
                    targetId = resolved.targetId,
                    islandId = resolved.islandId,
                    amount = resolved.progressAmount,
                    sourceQuestId = questId,
                ),
            )
            val questProgress = next.questBoard.active[questId]?.progress
                ?: throw IllegalStateException("Field objective disappeared after success")
            metadata["meta.questObjective"] = resolved.objectiveEventType.name
            metadata["meta.questObjectiveSourceQuest"] = questId
            metadata["meta.questObjectiveTarget"] = resolved.targetId
            metadata["meta.questObjectiveAmount"] = resolved.progressAmount.toString()
            metadata["meta.questProgress"] = questProgress.toString()
        }

        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = commandId,
                actorId = playerId,
                nextState = next,
                sourceFingerprint = fingerprint,
                metadata = metadata,
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
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

    private fun persist(event: CampaignEvent) {
        if (durableStore != null) durableStore.commit(event, hostReplica.state)
        else snapshotStore?.save(hostReplica.state)
    }
}
