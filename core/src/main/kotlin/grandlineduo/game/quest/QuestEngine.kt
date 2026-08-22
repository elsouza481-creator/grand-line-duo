package grandlineduo.game.quest

import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine

object QuestEngine {
    fun accept(world: WorldState, questId: String, actorId: String): WorldState {
        require(actorId in world.players) { "Unknown player $actorId" }
        require(questId !in world.questBoard.completedQuestIds) { "Quest already completed" }
        require(questId !in world.questBoard.failedQuestIds) { "Quest already failed" }
        require(questId !in world.questBoard.active) { "Quest already active" }
        val quest = world.questBoard.offers[questId]
            ?: throw IllegalArgumentException("Unknown offered quest $questId")
        require(quest.islandId == world.islandId) { "Quest is not available on the current island" }
        requireEligible(world, quest, actorId)

        val progress = QuestProgress(
            definition = quest,
            status = QuestStatus.ACTIVE,
            progress = 0,
            acceptedBy = actorId,
        )
        val board = world.questBoard.copy(
            offers = world.questBoard.offers - questId,
            active = world.questBoard.active + (questId to progress),
        )
        return world.copy(questBoard = board)
    }

    fun progress(world: WorldState, questId: String, amount: Int): WorldState {
        require(amount > 0) { "Quest progress amount must be positive" }
        val current = world.questBoard.active[questId]
            ?: throw IllegalArgumentException("Quest is not active: $questId")
        require(current.definition.type != QuestType.BOSS) {
            "Boss contracts progress only through boss victory"
        }
        require(current.status == QuestStatus.ACTIVE || current.status == QuestStatus.READY_TO_TURN_IN) {
            "Quest cannot progress from ${current.status}"
        }
        if (current.status == QuestStatus.READY_TO_TURN_IN) return world

        val nextProgress = (current.progress + amount).coerceAtMost(current.definition.requiredAmount)
        val nextStatus = if (nextProgress >= current.definition.requiredAmount) {
            QuestStatus.READY_TO_TURN_IN
        } else {
            QuestStatus.ACTIVE
        }
        val updated = current.copy(progress = nextProgress, status = nextStatus)
        return world.copy(
            questBoard = world.questBoard.copy(
                active = world.questBoard.active + (questId to updated),
            ),
        )
    }

    fun completeBossObjective(world: WorldState, questId: String): WorldState {
        val current = world.questBoard.active[questId]
            ?: throw IllegalArgumentException("Quest is not active: $questId")
        require(current.definition.type == QuestType.BOSS) { "Quest is not a boss contract" }
        require(current.status == QuestStatus.ACTIVE) { "Boss quest cannot complete from ${current.status}" }
        val updated = current.copy(
            progress = current.definition.requiredAmount,
            status = QuestStatus.READY_TO_TURN_IN,
        )
        return world.copy(
            questBoard = world.questBoard.copy(
                active = world.questBoard.active + (questId to updated),
            ),
        )
    }

    fun turnIn(world: WorldState, questId: String): WorldState {
        require(questId !in world.questBoard.completedQuestIds) { "Quest already completed" }
        val current = world.questBoard.active[questId]
            ?: throw IllegalArgumentException("Quest is not active: $questId")
        require(current.status == QuestStatus.READY_TO_TURN_IN) { "Quest is not ready to turn in" }

        val board = world.questBoard.copy(
            active = world.questBoard.active - questId,
            completedQuestIds = world.questBoard.completedQuestIds + questId,
        )
        var rewarded = world.copy(
            questBoard = board,
            partyBerries = world.partyBerries + current.definition.reward.berries,
        )
        rewarded = applyEvolutionReward(rewarded, current.definition.reward.evolutionPoints)
        rewarded = applyItemReward(rewarded, current.definition.reward)
        rewarded = applyFactionReward(rewarded, current.definition.reward)
        rewarded = applyWorldFlagReward(rewarded, current.definition.reward)
        return rewarded
    }

    fun fail(world: WorldState, questId: String, reason: String): WorldState {
        require(reason.isNotBlank()) { "Quest failure reason cannot be blank" }
        val current = world.questBoard.active[questId]
            ?: throw IllegalArgumentException("Quest is not active: $questId")
        require(current.status == QuestStatus.ACTIVE || current.status == QuestStatus.READY_TO_TURN_IN) {
            "Quest cannot fail from ${current.status}"
        }
        return world.copy(
            questBoard = world.questBoard.copy(
                active = world.questBoard.active - questId,
                failedQuestIds = world.questBoard.failedQuestIds + questId,
            ),
        )
    }

    fun isEligible(world: WorldState, quest: QuestDefinition, actorId: String): Boolean =
        runCatching { requireEligible(world, quest, actorId) }.isSuccess

    private fun requireEligible(world: WorldState, quest: QuestDefinition, actorId: String) {
        val actor = world.players[actorId] ?: throw IllegalArgumentException("Unknown player $actorId")
        val requirement = quest.requirement
        val totalBounty = world.players.values.sumOf { it.bounty }
        require(totalBounty >= requirement.minimumTotalBounty) { "Quest minimum bounty requirement not met" }

        requirement.minimumFactionStanding?.let { minimum ->
            val factionId = requirement.factionId
                ?: throw IllegalArgumentException("Quest faction requirement is missing faction id")
            val standing = world.socialState.factionStanding[factionId] ?: 0
            require(standing >= minimum) { "Quest faction standing requirement not met" }
        }

        requirement.requiredWorldFlag?.let { flag ->
            require(flagEnabled(world, flag)) { "Quest required world flag not present" }
        }

        requirement.requiredProfessionContains?.let { needle ->
            val profession = actor.profile?.profession.orEmpty()
            require(profession.contains(needle, ignoreCase = true)) { "Quest profession requirement not met" }
        }

        requirement.requiredCombatStyleContains?.let { needle ->
            val combatStyle = actor.profile?.combatStyle.orEmpty()
            require(combatStyle.contains(needle, ignoreCase = true)) { "Quest combat style requirement not met" }
        }
    }

    private fun flagEnabled(world: WorldState, flag: String): Boolean {
        val value = world.worldFlags[flag] ?: return false
        return value != "0" && !value.equals("false", ignoreCase = true) && value.isNotBlank()
    }

    private fun applyEvolutionReward(world: WorldState, amount: Int): WorldState {
        if (amount == 0) return world
        val players = world.players.mapValues { (_, player) ->
            val profile = player.profile
            if (profile == null) player
            else player.copy(profile = profile.copy(evolutionPoints = profile.evolutionPoints + amount))
        }
        return world.copy(players = players)
    }

    private fun applyItemReward(world: WorldState, reward: QuestReward): WorldState {
        val itemId = reward.itemId ?: return world
        if (reward.itemAmount <= 0) return world
        var next = world
        world.players.keys.sorted().forEach { playerId ->
            next = InventoryEngine.grant(next, playerId, itemId, reward.itemAmount)
        }
        return next
    }

    private fun applyFactionReward(world: WorldState, reward: QuestReward): WorldState {
        val factionId = reward.factionId ?: return world
        if (reward.factionStandingDelta == 0) return world
        val standings = world.socialState.factionStanding.toMutableMap()
        val current = standings[factionId] ?: 0
        standings[factionId] = (current + reward.factionStandingDelta).coerceIn(-100, 100)
        return world.copy(socialState = world.socialState.copy(factionStanding = standings))
    }

    private fun applyWorldFlagReward(world: WorldState, reward: QuestReward): WorldState {
        val flag = reward.worldFlag ?: return world
        return world.copy(worldFlags = world.worldFlags + (flag to "1"))
    }
}
