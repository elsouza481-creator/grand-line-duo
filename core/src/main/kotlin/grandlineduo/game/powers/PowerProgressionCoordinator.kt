package grandlineduo.game.powers

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.character.CharacterProfile
import grandlineduo.game.character.CharacterStateSync

/** Host-only boundary for persistent Haki and Devil Fruit progression. */
class PowerProgressionCoordinator(
    private val hostReplica: HostReplica,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun setLatentHaoshoku(
        commandId: String,
        playerId: String,
        hasPotential: Boolean,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId,
        playerId,
        "power-haoshoku-potential|$playerId|$hasPotential",
        hostTimestamp,
        mapOf("meta.power" to "HAOSHOKU_POTENTIAL_SET", "meta.playerId" to playerId),
    ) { profile -> profile.copy(haki = profile.haki.copy(latentHaoshoku = hasPotential)) }

    @Synchronized
    fun awakenHaki(
        commandId: String,
        playerId: String,
        type: HakiType,
        trigger: HakiTrigger,
        intensity: Int,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId,
        playerId,
        "power-haki-awaken|$playerId|${type.name}|${trigger.name}|$intensity",
        hostTimestamp,
        mapOf(
            "meta.power" to "HAKI_AWAKENED",
            "meta.playerId" to playerId,
            "meta.hakiType" to type.name,
            "meta.trigger" to trigger.name,
        ),
    ) { profile ->
        when (val result = HakiEngine.attemptAwakening(profile, profile.haki, type, trigger, intensity)) {
            is HakiAwakeningResult.Awakened -> profile.copy(haki = result.state)
            is HakiAwakeningResult.Rejected -> throw IllegalArgumentException("Haki awakening rejected: ${result.reason}")
        }
    }

    @Synchronized
    fun recordHakiUse(
        commandId: String,
        playerId: String,
        type: HakiType,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId,
        playerId,
        "power-haki-use|$playerId|${type.name}",
        hostTimestamp,
        mapOf("meta.power" to "HAKI_USED", "meta.playerId" to playerId, "meta.hakiType" to type.name),
    ) { profile -> profile.copy(haki = HakiEngine.recordUse(profile.haki, type)) }

    @Synchronized
    fun trainHakiMastery(
        commandId: String,
        playerId: String,
        type: HakiType,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId,
        playerId,
        "power-haki-train|$playerId|${type.name}",
        hostTimestamp,
        mapOf("meta.power" to "HAKI_MASTERY_ADVANCED", "meta.playerId" to playerId, "meta.hakiType" to type.name),
    ) { profile ->
        when (val result = HakiEngine.trainMastery(profile.haki, type)) {
            is HakiMasteryResult.Advanced -> profile.copy(haki = result.state)
            is HakiMasteryResult.Rejected -> throw IllegalArgumentException("Haki mastery rejected: ${result.reason}")
        }
    }

    @Synchronized
    fun consumeDevilFruit(
        commandId: String,
        playerId: String,
        definition: DevilFruitDefinition,
        identified: Boolean,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId,
        playerId,
        "power-fruit-consume|$playerId|${definition.id}|${definition.category.name}|${definition.displayName}|$identified",
        hostTimestamp,
        mapOf(
            "meta.power" to "DEVIL_FRUIT_CONSUMED",
            "meta.playerId" to playerId,
            "meta.fruitId" to definition.id,
            "meta.identified" to identified.toString(),
        ),
    ) { profile ->
        when (val result = DevilFruitEngine.consume(profile.devilFruit, definition, identified)) {
            is DevilFruitConsumeResult.Consumed -> profile.copy(devilFruit = result.state)
            is DevilFruitConsumeResult.Rejected -> throw IllegalArgumentException("Devil Fruit rejected: ${result.reason}")
        }
    }

    @Synchronized
    fun revealDevilFruit(
        commandId: String,
        playerId: String,
        definition: DevilFruitDefinition,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId,
        playerId,
        "power-fruit-reveal|$playerId|${definition.id}|${definition.displayName}",
        hostTimestamp,
        mapOf("meta.power" to "DEVIL_FRUIT_REVEALED", "meta.playerId" to playerId, "meta.fruitId" to definition.id),
    ) { profile ->
        val fruit = profile.devilFruit ?: throw IllegalArgumentException("Character has no Devil Fruit")
        profile.copy(devilFruit = DevilFruitEngine.revealIdentity(fruit, definition))
    }

    @Synchronized
    fun recordDevilFruitUse(
        commandId: String,
        playerId: String,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId,
        playerId,
        "power-fruit-use|$playerId",
        hostTimestamp,
        mapOf("meta.power" to "DEVIL_FRUIT_USED", "meta.playerId" to playerId),
    ) { profile ->
        val fruit = profile.devilFruit ?: throw IllegalArgumentException("Character has no Devil Fruit")
        profile.copy(devilFruit = DevilFruitEngine.recordUse(fruit))
    }

    @Synchronized
    fun trainDevilFruitMastery(
        commandId: String,
        playerId: String,
        hostTimestamp: Long,
    ): CampaignEvent = changeProfile(
        commandId,
        playerId,
        "power-fruit-train|$playerId",
        hostTimestamp,
        mapOf("meta.power" to "DEVIL_FRUIT_MASTERY_ADVANCED", "meta.playerId" to playerId),
    ) { profile ->
        val fruit = profile.devilFruit ?: throw IllegalArgumentException("Character has no Devil Fruit")
        when (val result = DevilFruitEngine.trainMastery(fruit)) {
            is DevilFruitMasteryResult.Advanced -> profile.copy(devilFruit = result.state)
            is DevilFruitMasteryResult.Rejected -> throw IllegalArgumentException("Devil Fruit mastery rejected: ${result.reason}")
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
        val nextProfile = transform(profile)
        val nextPlayer = CharacterStateSync.applyProfile(player, nextProfile)
        val nextWorld = before.copy(players = before.players + (playerId to nextPlayer))
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
