package grandlineduo.game.world

import grandlineduo.game.combat.EnemyAttackType

enum class ExplorationEnemyArchetype {
    BRUISER,
    SKIRMISHER,
    MARKSMAN,
    OFFICER,
}

data class ExplorationEnemyProfile(
    val name: String,
    val maxHp: Int,
    val attackPower: Int,
    val rewardBerries: Long,
    val rewardItemId: String,
    val rewardItemAmount: Int = 1,
    val rewardMasteryExperience: Int,
    val initialAttackType: EnemyAttackType,
    val respawnSteps: Int,
)

object ExplorationEnemyCatalog {
    fun profile(archetype: ExplorationEnemyArchetype, danger: Int): ExplorationEnemyProfile {
        val d = danger.coerceIn(1, 10)
        return when (archetype) {
            ExplorationEnemyArchetype.BRUISER -> ExplorationEnemyProfile(
                name = "Brutamontes do Quebra-Mar",
                maxHp = 42 + d * 10,
                attackPower = 5 + d * 2,
                rewardBerries = 400L + d * 140L,
                rewardItemId = "ration",
                rewardMasteryExperience = 10 + d * 2,
                initialAttackType = EnemyAttackType.HEAVY_STRIKE,
                respawnSteps = 16 + d,
            )
            ExplorationEnemyArchetype.SKIRMISHER -> ExplorationEnemyProfile(
                name = "Saqueadores da Maré",
                maxHp = 34 + d * 8,
                attackPower = 7 + d * 2,
                rewardBerries = 350L + d * 150L,
                rewardItemId = "bandage",
                rewardMasteryExperience = 9 + d * 2,
                initialAttackType = EnemyAttackType.SWEEP,
                respawnSteps = 12 + d,
            )
            ExplorationEnemyArchetype.MARKSMAN -> ExplorationEnemyProfile(
                name = "Atiradores do Cais",
                maxHp = 26 + d * 6,
                attackPower = 9 + d * 3,
                rewardBerries = 450L + d * 160L,
                rewardItemId = "energy_tonic",
                rewardMasteryExperience = 11 + d * 2,
                initialAttackType = EnemyAttackType.HEAVY_STRIKE,
                respawnSteps = 14 + d,
            )
            ExplorationEnemyArchetype.OFFICER -> ExplorationEnemyProfile(
                name = "Oficial Mercenário da Rota",
                maxHp = 38 + d * 9,
                attackPower = 8 + d * 2,
                rewardBerries = 550L + d * 180L,
                rewardItemId = if (d >= 8) "kairouseki_shard" else "energy_tonic",
                rewardMasteryExperience = 14 + d * 3,
                initialAttackType = EnemyAttackType.SWEEP,
                respawnSteps = 20 + d,
            )
        }
    }

    /**
     * Original primary selector. Kept byte-for-byte compatible with the single-enemy map so the
     * existing east encounter keeps the same archetype/reward for saves created before multi-mob maps.
     */
    fun select(campaignId: String, islandId: String): ExplorationEnemyArchetype {
        require(campaignId.isNotBlank()) { "Campaign id is required" }
        require(islandId.isNotBlank()) { "Island id is required" }
        var hash = 0xCBF29CE484222325UL.toLong()
        "$campaignId|$islandId|enemy-archetype-v1".forEach { ch ->
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001B3L
        }
        val mixed = (hash xor (hash ushr 32)).toInt() and Int.MAX_VALUE
        return ExplorationEnemyArchetype.entries[mixed % ExplorationEnemyArchetype.entries.size]
    }

    /**
     * Multi-encounter selector. East is the legacy primary; west and north rotate from that base,
     * guaranteeing three distinct roles while remaining deterministic for every campaign/island.
     */
    fun select(campaignId: String, islandId: String, slotId: String): ExplorationEnemyArchetype {
        val offset = when (slotId.lowercase()) {
            "east" -> 0
            "west" -> 1
            "north" -> 2
            else -> throw IllegalArgumentException("Unknown exploration enemy slot $slotId")
        }
        val base = select(campaignId, islandId)
        return ExplorationEnemyArchetype.entries[(base.ordinal + offset) % ExplorationEnemyArchetype.entries.size]
    }
}
