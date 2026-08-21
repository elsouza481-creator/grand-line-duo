package grandlineduo.game.quest

object QuestCanonicalState {
    fun encode(board: QuestBoardState): String {
        if (board == QuestBoardState()) return ""
        return buildString {
            field("questVersion", "1")
            field("questGeneration", board.generationIndex.toString())

            append("questOffers=").append(board.offers.size).append(';')
            board.offers.toSortedMap().forEach { (questId, definition) ->
                field("questOfferKey", questId)
                appendDefinition(definition)
            }

            append("questActive=").append(board.active.size).append(';')
            board.active.toSortedMap().forEach { (questId, progress) ->
                field("questActiveKey", questId)
                appendDefinition(progress.definition)
                field("questStatus", progress.status.name)
                field("questProgress", progress.progress.toString())
                field("questAcceptedBy", progress.acceptedBy ?: "")
                field("questFailureReason", progress.failureReason ?: "")
            }

            append("questCompleted=").append(board.completedQuestIds.size).append(';')
            board.completedQuestIds.sorted().forEach { field("questCompletedId", it) }

            append("questFailed=").append(board.failedQuestIds.size).append(';')
            board.failedQuestIds.sorted().forEach { field("questFailedId", it) }
        }
    }

    private fun StringBuilder.appendDefinition(quest: QuestDefinition) {
        field("questId", quest.questId)
        field("questIslandId", quest.islandId)
        field("questTitle", quest.title)
        field("questType", quest.type.name)
        field("questRarity", quest.rarity.name)
        field("questIssuerFaction", quest.issuerFaction)
        field("questTargetId", quest.targetId)
        field("questRequiredAmount", quest.requiredAmount.toString())

        val requirement = quest.requirement
        field("questReqFaction", requirement.factionId ?: "")
        field("questReqStanding", requirement.minimumFactionStanding?.toString() ?: "")
        field("questReqBounty", requirement.minimumTotalBounty.toString())
        field("questReqFlag", requirement.requiredWorldFlag ?: "")
        field("questReqProfession", requirement.requiredProfessionContains ?: "")
        field("questReqCombatStyle", requirement.requiredCombatStyleContains ?: "")

        val reward = quest.reward
        field("questRewardBerries", reward.berries.toString())
        field("questRewardEvolution", reward.evolutionPoints.toString())
        field("questRewardItem", reward.itemId ?: "")
        field("questRewardItemAmount", reward.itemAmount.toString())
        field("questRewardFaction", reward.factionId ?: "")
        field("questRewardStanding", reward.factionStandingDelta.toString())
        field("questRewardFlag", reward.worldFlag ?: "")
        field("questExpiresAfter", quest.expiresAfterGeneration?.toString() ?: "")
    }

    private fun StringBuilder.field(name: String, value: String) {
        append(name.length).append(':').append(name)
        append(value.length).append(':').append(value).append(';')
    }
}
