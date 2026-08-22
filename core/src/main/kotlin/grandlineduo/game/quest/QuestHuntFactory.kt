package grandlineduo.game.quest

import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph
import java.util.Random

object QuestHuntFactory {
    fun create(world: WorldState, progress: QuestProgress, campaignSeed: Long): CombatState {
        val quest = progress.definition
        require(quest.type == QuestType.HUNT) { "Quest is not a hunt contract" }
        require(progress.status == QuestStatus.ACTIVE) { "Hunt quest is not active" }
        require(quest.islandId == world.islandId) { "Hunt quest is not on the current island" }
        val p1 = world.players["p1"] ?: throw IllegalArgumentException("Missing p1")
        val p2 = world.players["p2"] ?: throw IllegalArgumentException("Missing p2")
        val encounter = encounterIndex(progress)
        val (hp, attack) = stats(quest.rarity)
        val random = Random(combatSeed(progress, campaignSeed))
        val target = if (random.nextBoolean()) "p1" else "p2"
        val type = if (random.nextBoolean()) EnemyAttackType.HEAVY_STRIKE else EnemyAttackType.SWEEP
        val name = quest.title.substringAfter(": ").ifBlank { quest.targetId.replace('-', ' ') }

        return CombatState(
            round = 1,
            players = mapOf(
                "p1" to Combatant("p1", p1.name, p1.hp, p1.maxHp),
                "p2" to Combatant("p2", p2.name, p2.hp, p2.maxHp),
            ),
            enemy = EnemyCombatant(
                id = "${quest.targetId}-hunt-$encounter",
                name = name,
                hp = hp,
                maxHp = hp,
                attackPower = attack,
            ),
            telegraph = EnemyTelegraph(type, target),
            status = CombatStatus.ACTIVE,
        )
    }

    fun progressPerVictory(rarity: QuestRarity): Int = when (rarity) {
        QuestRarity.COMMON -> 1
        QuestRarity.RARE -> 2
        QuestRarity.EPIC -> 3
        QuestRarity.LEGENDARY -> 4
    }

    fun encounterIndex(progress: QuestProgress): Int =
        progress.progress / progressPerVictory(progress.definition.rarity) + 1

    fun combatSeed(progress: QuestProgress, campaignSeed: Long): Long {
        val quest = progress.definition
        return campaignSeed xor
            (quest.questId.hashCode().toLong() * 6364136223846793005L) xor
            (quest.targetId.hashCode().toLong() * -7046029254386353131L) xor
            (quest.rarity.ordinal.toLong() shl 41) xor
            (encounterIndex(progress).toLong() * 104729L)
    }

    private fun stats(rarity: QuestRarity): Pair<Int, Int> = when (rarity) {
        QuestRarity.COMMON -> 48 to 9
        QuestRarity.RARE -> 72 to 12
        QuestRarity.EPIC -> 100 to 15
        QuestRarity.LEGENDARY -> 135 to 18
    }
}
