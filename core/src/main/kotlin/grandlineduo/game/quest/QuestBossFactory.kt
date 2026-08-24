package grandlineduo.game.quest

import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph
import java.util.Random

object QuestBossFactory {
    fun create(world: WorldState, quest: QuestDefinition, campaignSeed: Long): CombatState {
        require(quest.type == QuestType.BOSS) { "Quest is not a boss contract" }
        require(quest.islandId == world.islandId) { "Boss quest is not on the current island" }
        val p1 = world.players["p1"] ?: throw IllegalArgumentException("Missing p1")
        val p2 = world.players["p2"] ?: throw IllegalArgumentException("Missing p2")
        val (hp, attack) = stats(quest.rarity)
        val random = Random(combatSeed(quest, campaignSeed))
        val target = if (random.nextBoolean()) "p1" else "p2"
        val type = if (random.nextBoolean()) EnemyAttackType.HEAVY_STRIKE else EnemyAttackType.SWEEP
        val name = quest.title.substringAfter(": ").ifBlank { quest.targetId.replace('-', ' ') }

        return CombatState(
            round = 1,
            players = mapOf(
                "p1" to Combatant("p1", p1.name, p1.hp, p1.maxHp),
                "p2" to Combatant("p2", p2.name, p2.hp, p2.maxHp),
            ),
            enemy = EnemyCombatant(quest.targetId, name, hp, hp, attack),
            telegraph = EnemyTelegraph(type, target),
            status = CombatStatus.ACTIVE,
        )
    }

    fun combatSeed(quest: QuestDefinition, campaignSeed: Long): Long =
        campaignSeed xor (quest.questId.hashCode().toLong() shl 17) xor quest.targetId.hashCode().toLong()

    private fun stats(rarity: QuestRarity): Pair<Int, Int> = when (rarity) {
        QuestRarity.COMMON -> 72 to 11
        QuestRarity.RARE -> 108 to 14
        QuestRarity.EPIC -> 150 to 18
        QuestRarity.LEGENDARY -> 200 to 22
    }
}
