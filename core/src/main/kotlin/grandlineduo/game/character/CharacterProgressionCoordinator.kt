package grandlineduo.game.character

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore

/**
 * Host-only progression boundary. Player clients may choose training through gameplay/UI flows,
 * but only the authoritative host/GM converts those validated outcomes into persistent PEV and
 * stat changes.
 */
class CharacterProgressionCoordinator(
    private val hostReplica: HostReplica,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun awardEvolutionPoints(
        commandId: String,
        playerId: String,
        amount: Int,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId = commandId,
        playerId = playerId,
        fingerprint = "progression-award|$playerId|$amount",
        hostTimestamp = hostTimestamp,
        metadata = mapOf(
            "meta.progression" to "PEV_AWARDED",
            "meta.playerId" to playerId,
            "meta.amount" to amount.toString(),
        ),
    ) { ProgressionEngine.awardEvolutionPoints(it, amount) }

    @Synchronized
    fun recordAttributeTraining(
        commandId: String,
        playerId: String,
        attribute: Attribute,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId = commandId,
        playerId = playerId,
        fingerprint = "progression-train-attribute|$playerId|${attribute.name}",
        hostTimestamp = hostTimestamp,
        metadata = mapOf(
            "meta.progression" to "ATTRIBUTE_TRAINED",
            "meta.playerId" to playerId,
            "meta.attribute" to attribute.name,
        ),
    ) { ProgressionEngine.markAttributeTraining(it, attribute) }

    @Synchronized
    fun recordSkillTraining(
        commandId: String,
        playerId: String,
        skill: Skill,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId = commandId,
        playerId = playerId,
        fingerprint = "progression-train-skill|$playerId|${skill.name}",
        hostTimestamp = hostTimestamp,
        metadata = mapOf(
            "meta.progression" to "SKILL_TRAINED",
            "meta.playerId" to playerId,
            "meta.skill" to skill.name,
        ),
    ) { ProgressionEngine.markSkillTraining(it, skill) }

    @Synchronized
    fun increaseAttribute(
        commandId: String,
        playerId: String,
        attribute: Attribute,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId = commandId,
        playerId = playerId,
        fingerprint = "progression-increase-attribute|$playerId|${attribute.name}",
        hostTimestamp = hostTimestamp,
        metadata = mapOf(
            "meta.progression" to "ATTRIBUTE_INCREASED",
            "meta.playerId" to playerId,
            "meta.attribute" to attribute.name,
        ),
    ) { profile ->
        when (val result = ProgressionEngine.increaseAttribute(profile, attribute)) {
            is ProgressionResult.Success -> result.profile
            is ProgressionResult.Rejected -> throw IllegalArgumentException("Progression rejected: ${result.error}")
        }
    }

    @Synchronized
    fun increaseSkill(
        commandId: String,
        playerId: String,
        skill: Skill,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId = commandId,
        playerId = playerId,
        fingerprint = "progression-increase-skill|$playerId|${skill.name}",
        hostTimestamp = hostTimestamp,
        metadata = mapOf(
            "meta.progression" to "SKILL_INCREASED",
            "meta.playerId" to playerId,
            "meta.skill" to skill.name,
        ),
    ) { profile ->
        when (val result = ProgressionEngine.increaseSkill(profile, skill)) {
            is ProgressionResult.Success -> result.profile
            is ProgressionResult.Rejected -> throw IllegalArgumentException("Progression rejected: ${result.error}")
        }
    }

    private fun changeProfile(
        commandId: String,
        playerId: String,
        fingerprint: String,
        hostTimestamp: Long,
        metadata: Map<String, String>,
        transform: (CharacterProfile) -> CharacterProfile,
    ): CampaignEvent {
        hostReplica.events.firstOrNull { it.commandId == commandId }?.let { existing ->
            require(existing.commandFingerprint == fingerprint) { "Command ID collision" }
            persist(existing)
            return existing
        }

        val before = hostReplica.state
        val player = before.players[playerId] ?: throw IllegalArgumentException("Unknown player $playerId")
        val profile = player.profile ?: throw IllegalArgumentException("Character not created for $playerId")
        val updatedProfile = transform(profile)
        val updatedPlayer = CharacterStateSync.applyProfile(player, updatedProfile)
        val nextWorld = before.copy(players = before.players + (playerId to updatedPlayer))
        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = commandId,
                actorId = "gm",
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = metadata,
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun persist(event: CampaignEvent) {
        if (durableStore != null) durableStore.commit(event, hostReplica.state)
        else snapshotStore?.save(hostReplica.state)
    }
}
