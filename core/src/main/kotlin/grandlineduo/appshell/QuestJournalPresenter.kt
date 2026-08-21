package grandlineduo.appshell

import grandlineduo.core.model.WorldState
import grandlineduo.game.ItemCatalog
import grandlineduo.game.world.ExplorationEnemyRank
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationQuestEngine
import grandlineduo.game.world.ExplorationQuestStatus

data class QuestJournalEntry(
    val id: String,
    val title: String,
    val giver: String,
    val status: ExplorationQuestStatus,
    val objective: String,
    val reward: String,
    val isBossHunt: Boolean,
)

/** Read-only projection of the authoritative free-roam quest state for one player. */
object QuestJournalPresenter {
    fun entries(world: WorldState, playerId: String): List<QuestJournalEntry> {
        require(playerId in world.players) { "Unknown player $playerId" }
        val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
        val boss = map.enemies.values.firstOrNull { it.rank == ExplorationEnemyRank.FIELD_BOSS }

        return map.npcs.values
            .mapNotNull { npc ->
                val questId = npc.questId ?: return@mapNotNull null
                val status = ExplorationQuestEngine.status(world, playerId, questId)
                val bossHunt = ExplorationQuestEngine.isBossHuntQuest(questId)
                val objective = map.questObjectives.values.firstOrNull { it.questId == questId }
                val title = if (bossHunt) {
                    "Caçada: ${boss?.name ?: "Chefe de campo"}"
                } else {
                    "Recuperação: ${objective?.label ?: "Carga perdida"}"
                }
                val objectiveText = when (status) {
                    ExplorationQuestStatus.AVAILABLE -> "Fale com ${npc.name} para aceitar."
                    ExplorationQuestStatus.ACTIVE -> if (bossHunt) {
                        "Derrote ${boss?.name ?: "o chefe de campo"} e sobreviva ao combate."
                    } else {
                        "Encontre ${objective?.label ?: "o objetivo perdido"} e investigue o local."
                    }
                    ExplorationQuestStatus.OBJECTIVE_COMPLETE -> "Volte a ${npc.name} para entregar a missão."
                    ExplorationQuestStatus.TURNED_IN -> "Concluída e recompensa recebida."
                }
                val rewardText = if (bossHunt) {
                    "${ExplorationQuestEngine.BOSS_REWARD_BERRIES} Berries + ${ItemCatalog.get(ExplorationQuestEngine.BOSS_REWARD_ITEM_ID).name}"
                } else {
                    "${ExplorationQuestEngine.REWARD_BERRIES} Berries + ${ItemCatalog.get(ExplorationQuestEngine.REWARD_ITEM_ID).name}"
                }
                QuestJournalEntry(
                    id = questId,
                    title = title,
                    giver = npc.name,
                    status = status,
                    objective = objectiveText,
                    reward = rewardText,
                    isBossHunt = bossHunt,
                )
            }
            .sortedWith(compareByDescending<QuestJournalEntry> { it.status == ExplorationQuestStatus.ACTIVE || it.status == ExplorationQuestStatus.OBJECTIVE_COMPLETE }
                .thenByDescending { it.isBossHunt }
                .thenBy { it.id })
    }
}
