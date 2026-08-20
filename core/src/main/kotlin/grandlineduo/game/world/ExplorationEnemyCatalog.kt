package grandlineduo.game.world

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
            )
            ExplorationEnemyArchetype.SKIRMISHER -> ExplorationEnemyProfile(
                name = "Saqueadores da Maré",
                maxHp = 34 + d * 8,
                attackPower = 7 + d * 2,
                rewardBerries = 350L + d * 150L,
            )
            ExplorationEnemyArchetype.MARKSMAN -> ExplorationEnemyProfile(
                name = "Atiradores do Cais",
                maxHp = 26 + d * 6,
                attackPower = 9 + d * 3,
                rewardBerries = 450L + d * 160L,
            )
            ExplorationEnemyArchetype.OFFICER -> ExplorationEnemyProfile(
                name = "Oficial Mercenário da Rota",
                maxHp = 38 + d * 9,
                attackPower = 8 + d * 2,
                rewardBerries = 550L + d * 180L,
            )
        }
    }

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
}
