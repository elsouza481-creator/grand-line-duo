package grandlineduo.game.powers

data class FruitDiscovery(
    val chapter: Int,
    val definition: DevilFruitDefinition,
)

/** Deterministic campaign-level rarity so saves/reconnects never reroll power potential or fruit loot. */
object PowerDiscoveryEngine {
    private val fruits = listOf(
        DevilFruitDefinition("pulse-pulse", "Fruta Pulso-Pulso", DevilFruitCategory.PARAMECIA),
        DevilFruitDefinition("forge-forge", "Fruta Forja-Forja", DevilFruitCategory.PARAMECIA),
        DevilFruitDefinition("mist-mist", "Fruta Nevoa-Nevoa", DevilFruitCategory.LOGIA),
        DevilFruitDefinition("albatross-albatross", "Fruta Ave-Ave, Modelo Albatroz", DevilFruitCategory.ZOAN),
        DevilFruitDefinition("reef-reef", "Fruta Recife-Recife", DevilFruitCategory.PARAMECIA),
    )

    fun definition(id: String): DevilFruitDefinition =
        fruits.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown Devil Fruit $id")

    fun fruitDiscovery(campaignSeed: Long): FruitDiscovery {
        val chapter = 2 + Math.floorMod(mix(campaignSeed, "fruit-chapter"), 3L).toInt()
        val index = Math.floorMod(mix(campaignSeed, "fruit-id"), fruits.size.toLong()).toInt()
        return FruitDiscovery(chapter, fruits[index])
    }

    /** About one in sixty-four characters has latent Haoshoku potential. */
    fun hasLatentHaoshoku(campaignSeed: Long, playerId: String): Boolean =
        Math.floorMod(mix(campaignSeed, "haoshoku:$playerId"), 64L) == 0L

    private fun mix(seed: Long, salt: String): Long {
        var value = seed xor 0x5DEECE66DL
        for (ch in salt) {
            value = value * 6364136223846793005L + ch.code.toLong() + 1442695040888963407L
            value = value xor (value ushr 29)
        }
        return value xor (value ushr 33)
    }
}
