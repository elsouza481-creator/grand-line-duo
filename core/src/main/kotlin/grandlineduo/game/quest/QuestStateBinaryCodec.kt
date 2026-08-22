package grandlineduo.game.quest

import java.io.DataInputStream
import java.io.DataOutputStream

object QuestStateBinaryCodec {
    private const val MAX_QUESTS = 10_000

    fun write(data: DataOutputStream, board: QuestBoardState) {
        require(board.generationIndex >= 0) { "Invalid quest generation index" }
        data.writeLong(board.generationIndex)

        data.writeInt(board.offers.size)
        board.offers.toSortedMap().forEach { (questId, definition) ->
            require(questId == definition.questId) { "Quest offer key mismatch" }
            data.writeUTF(questId)
            writeDefinition(data, definition)
        }

        data.writeInt(board.active.size)
        board.active.toSortedMap().forEach { (questId, progress) ->
            require(questId == progress.definition.questId) { "Active quest key mismatch" }
            data.writeUTF(questId)
            writeProgress(data, progress)
        }

        data.writeInt(board.completedQuestIds.size)
        board.completedQuestIds.sorted().forEach(data::writeUTF)

        data.writeInt(board.failedQuestIds.size)
        board.failedQuestIds.sorted().forEach(data::writeUTF)
    }

    fun read(data: DataInputStream): QuestBoardState {
        val generationIndex = data.readLong()
        require(generationIndex >= 0) { "Invalid quest generation index" }

        val offerCount = readCount(data, "quest offer")
        val offers = linkedMapOf<String, QuestDefinition>()
        repeat(offerCount) {
            val key = data.readUTF()
            require(key !in offers) { "Duplicate quest offer $key" }
            val definition = readDefinition(data)
            require(key == definition.questId) { "Quest offer key mismatch" }
            offers[key] = definition
        }

        val activeCount = readCount(data, "active quest")
        val active = linkedMapOf<String, QuestProgress>()
        repeat(activeCount) {
            val key = data.readUTF()
            require(key !in active) { "Duplicate active quest $key" }
            val progress = readProgress(data)
            require(key == progress.definition.questId) { "Active quest key mismatch" }
            require(progress.status == QuestStatus.ACTIVE || progress.status == QuestStatus.READY_TO_TURN_IN) {
                "Invalid active quest status"
            }
            active[key] = progress
        }

        val completedCount = readCount(data, "completed quest")
        val completed = linkedSetOf<String>()
        repeat(completedCount) {
            require(completed.add(data.readUTF())) { "Duplicate completed quest" }
        }

        val failedCount = readCount(data, "failed quest")
        val failed = linkedSetOf<String>()
        repeat(failedCount) {
            require(failed.add(data.readUTF())) { "Duplicate failed quest" }
        }

        require((offers.keys intersect active.keys).isEmpty()) { "Quest cannot be both offered and active" }
        require((completed intersect failed).isEmpty()) { "Quest cannot be both completed and failed" }
        require((completed intersect active.keys).isEmpty()) { "Completed quest cannot remain active" }
        require((failed intersect active.keys).isEmpty()) { "Failed quest cannot remain active" }

        return QuestBoardState(
            generationIndex = generationIndex,
            offers = offers,
            active = active,
            completedQuestIds = completed,
            failedQuestIds = failed,
        )
    }

    private fun writeDefinition(data: DataOutputStream, quest: QuestDefinition) {
        data.writeUTF(quest.questId)
        data.writeUTF(quest.islandId)
        data.writeUTF(quest.title)
        data.writeUTF(quest.type.name)
        data.writeUTF(quest.rarity.name)
        data.writeUTF(quest.issuerFaction)
        data.writeUTF(quest.targetId)
        data.writeInt(quest.requiredAmount)
        writeRequirement(data, quest.requirement)
        writeReward(data, quest.reward)
        writeNullableLong(data, quest.expiresAfterGeneration)
    }

    private fun readDefinition(data: DataInputStream): QuestDefinition = QuestDefinition(
        questId = data.readUTF(),
        islandId = data.readUTF(),
        title = data.readUTF(),
        type = QuestType.valueOf(data.readUTF()),
        rarity = QuestRarity.valueOf(data.readUTF()),
        issuerFaction = data.readUTF(),
        targetId = data.readUTF(),
        requiredAmount = data.readInt(),
        requirement = readRequirement(data),
        reward = readReward(data),
        expiresAfterGeneration = readNullableLong(data),
    )

    private fun writeRequirement(data: DataOutputStream, requirement: QuestRequirement) {
        writeNullableString(data, requirement.factionId)
        data.writeBoolean(requirement.minimumFactionStanding != null)
        requirement.minimumFactionStanding?.let(data::writeInt)
        data.writeLong(requirement.minimumTotalBounty)
        writeNullableString(data, requirement.requiredWorldFlag)
        writeNullableString(data, requirement.requiredProfessionContains)
        writeNullableString(data, requirement.requiredCombatStyleContains)
    }

    private fun readRequirement(data: DataInputStream): QuestRequirement = QuestRequirement(
        factionId = readNullableString(data),
        minimumFactionStanding = if (data.readBoolean()) data.readInt() else null,
        minimumTotalBounty = data.readLong(),
        requiredWorldFlag = readNullableString(data),
        requiredProfessionContains = readNullableString(data),
        requiredCombatStyleContains = readNullableString(data),
    )

    private fun writeReward(data: DataOutputStream, reward: QuestReward) {
        data.writeLong(reward.berries)
        data.writeInt(reward.evolutionPoints)
        writeNullableString(data, reward.itemId)
        data.writeInt(reward.itemAmount)
        writeNullableString(data, reward.factionId)
        data.writeInt(reward.factionStandingDelta)
        writeNullableString(data, reward.worldFlag)
    }

    private fun readReward(data: DataInputStream): QuestReward = QuestReward(
        berries = data.readLong(),
        evolutionPoints = data.readInt(),
        itemId = readNullableString(data),
        itemAmount = data.readInt(),
        factionId = readNullableString(data),
        factionStandingDelta = data.readInt(),
        worldFlag = readNullableString(data),
    )

    private fun writeProgress(data: DataOutputStream, progress: QuestProgress) {
        writeDefinition(data, progress.definition)
        data.writeUTF(progress.status.name)
        data.writeInt(progress.progress)
        writeNullableString(data, progress.acceptedBy)
        writeNullableString(data, progress.failureReason)
    }

    private fun readProgress(data: DataInputStream): QuestProgress {
        val definition = readDefinition(data)
        val status = QuestStatus.valueOf(data.readUTF())
        val progress = data.readInt()
        require(progress in 0..definition.requiredAmount) { "Invalid quest progress" }
        val acceptedBy = readNullableString(data)
        require(acceptedBy == null || acceptedBy == "p1" || acceptedBy == "p2") { "Invalid quest accepting player" }
        return QuestProgress(
            definition = definition,
            status = status,
            progress = progress,
            acceptedBy = acceptedBy,
            failureReason = readNullableString(data),
        )
    }

    private fun writeNullableString(data: DataOutputStream, value: String?) {
        data.writeBoolean(value != null)
        value?.let(data::writeUTF)
    }

    private fun readNullableString(data: DataInputStream): String? =
        if (data.readBoolean()) data.readUTF() else null

    private fun writeNullableLong(data: DataOutputStream, value: Long?) {
        data.writeBoolean(value != null)
        value?.let(data::writeLong)
    }

    private fun readNullableLong(data: DataInputStream): Long? =
        if (data.readBoolean()) data.readLong() else null

    private fun readCount(data: DataInputStream, label: String): Int {
        val count = data.readInt()
        require(count in 0..MAX_QUESTS) { "Invalid $label count" }
        return count
    }
}
