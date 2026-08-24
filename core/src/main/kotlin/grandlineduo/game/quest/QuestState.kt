package grandlineduo.game.quest

enum class QuestType {
    HUNT,
    EXPLORE,
    COLLECT,
    RESCUE,
    ESCORT,
    INVESTIGATE,
    BOSS,
}

enum class QuestRarity {
    COMMON,
    RARE,
    EPIC,
    LEGENDARY,
}

enum class QuestStatus {
    OFFERED,
    ACTIVE,
    READY_TO_TURN_IN,
    COMPLETED,
    FAILED,
}

data class QuestRequirement(
    val factionId: String? = null,
    val minimumFactionStanding: Int? = null,
    val minimumTotalBounty: Long = 0,
    val requiredWorldFlag: String? = null,
    val requiredProfessionContains: String? = null,
    val requiredCombatStyleContains: String? = null,
)

data class QuestReward(
    val berries: Long = 0,
    val evolutionPoints: Int = 0,
    val itemId: String? = null,
    val itemAmount: Int = 0,
    val factionId: String? = null,
    val factionStandingDelta: Int = 0,
    val worldFlag: String? = null,
)

data class QuestDefinition(
    val questId: String,
    val islandId: String,
    val title: String,
    val type: QuestType,
    val rarity: QuestRarity,
    val issuerFaction: String,
    val targetId: String,
    val requiredAmount: Int,
    val requirement: QuestRequirement = QuestRequirement(),
    val reward: QuestReward = QuestReward(),
    val expiresAfterGeneration: Long? = null,
) {
    init {
        require(questId.isNotBlank()) { "Quest id cannot be blank" }
        require(islandId.isNotBlank()) { "Quest island cannot be blank" }
        require(title.isNotBlank()) { "Quest title cannot be blank" }
        require(issuerFaction.isNotBlank()) { "Quest issuer cannot be blank" }
        require(targetId.isNotBlank()) { "Quest target cannot be blank" }
        require(requiredAmount > 0) { "Quest required amount must be positive" }
        require(requirement.minimumTotalBounty >= 0) { "Quest minimum bounty cannot be negative" }
        requirement.minimumFactionStanding?.let {
            require(it in -100..100) { "Quest minimum faction standing must be in -100..100" }
        }
        require(reward.berries >= 0) { "Quest berry reward cannot be negative" }
        require(reward.evolutionPoints >= 0) { "Quest evolution reward cannot be negative" }
        require(reward.itemAmount >= 0) { "Quest item reward amount cannot be negative" }
        require(reward.itemId != null || reward.itemAmount == 0) { "Quest item amount requires an item id" }
    }
}

data class QuestProgress(
    val definition: QuestDefinition,
    val status: QuestStatus,
    val progress: Int = 0,
    val acceptedBy: String? = null,
    val failureReason: String? = null,
)

data class QuestBoardState(
    val generationIndex: Long = 0,
    val offers: Map<String, QuestDefinition> = emptyMap(),
    val active: Map<String, QuestProgress> = emptyMap(),
    val completedQuestIds: Set<String> = emptySet(),
    val failedQuestIds: Set<String> = emptySet(),
)
