package grandlineduo.game.world

import java.util.Random

data class WorldIsland(
    val id: String,
    val name: String,
    val climate: String,
    val danger: Int,
    val factions: Set<String>,
    val flags: Set<String> = emptySet(),
)

/**
 * Deterministic open-world atlas. Stormglass exposes curated starter routes; every later
 * voyage creates three stable procedural destinations, so the sea never exhausts a finite list.
 */
object GrandLineWorldAtlas {
    private val curated = listOf(
        WorldIsland("stormglass-cay", "Stormglass Cay", "Tempestade costeira", 2, setOf("MARINES")),
        WorldIsland("emberwake", "Emberwake", "Vulcânico", 3, setOf("PIRATES")),
        WorldIsland("brineveil", "Brineveil", "Névoa oceânica", 4, setOf("MARINES")),
        WorldIsland("gearfall", "Gearfall", "Industrial", 4, setOf("UNDERWORLD")),
        WorldIsland("hollow-crown", "Hollow Crown", "Ruínas tropicais", 6, setOf("PIRATES"), setOf("ANCIENT_RUINS")),
        WorldIsland("meridian-vault", "Meridian Vault", "Árido e magnético", 7, setOf("MARINES", "UNDERWORLD"), setOf("ANCIENT_RUINS")),
    ).associateBy { it.id }

    private val prefixes = listOf(
        "Crimson", "Azure", "Ivory", "Obsidian", "Golden", "Silent", "Storm", "Moon", "Iron", "Wild",
        "Broken", "Burning", "Frozen", "Sunken", "Emerald", "Black", "Silver", "Scarlet", "Lost", "Thunder",
    )
    private val suffixes = listOf(
        "Reef", "Crown", "Atoll", "Harbor", "Fang", "Reach", "Haven", "Spire", "Key", "Bastion",
        "Lagoon", "Cradle", "Ridge", "Vault", "Cape", "Isle", "Delta", "Step", "Watch", "Garden",
    )
    private val climates = listOf(
        "Tropical", "Glacial", "Desértico", "Monções", "Vulcânico", "Floresta úmida", "Tempestade permanente",
        "Névoa densa", "Outono eterno", "Recifes quentes", "Pântano salgado", "Montanhas ventosas",
    )
    private val factionPatterns = listOf(
        setOf("PIRATES"),
        setOf("MARINES"),
        setOf("UNDERWORLD"),
        setOf("PIRATES", "UNDERWORLD"),
        setOf("MARINES", "UNDERWORLD"),
        setOf("PIRATES", "MARINES"),
    )

    fun availableDestinations(
        campaignId: String,
        currentIslandId: String,
        voyageIndex: Int,
    ): List<WorldIsland> {
        require(campaignId.isNotBlank()) { "Campaign id is required" }
        require(currentIslandId.isNotBlank()) { "Current island is required" }
        require(voyageIndex >= 0) { "Voyage index cannot be negative" }

        if (currentIslandId == "stormglass-cay" && voyageIndex == 0) {
            return listOf("emberwake", "brineveil", "gearfall").map { curated.getValue(it) }
        }

        return (0 until 3).map { slot ->
            val routeSeed = mixSeed(campaignId, currentIslandId, voyageIndex, slot)
            val id = "route-$voyageIndex-$slot-${java.lang.Long.toUnsignedString(routeSeed, 16)}"
            describe(campaignId, id)
        }
    }

    fun describe(campaignId: String, islandId: String): WorldIsland {
        require(campaignId.isNotBlank()) { "Campaign id is required" }
        require(islandId.isNotBlank()) { "Island id is required" }
        curated[islandId]?.let { return it }

        val random = Random(mixSeed(campaignId, islandId))
        val prefix = prefixes[random.nextInt(prefixes.size)]
        val suffix = suffixes[random.nextInt(suffixes.size)]
        val climate = climates[random.nextInt(climates.size)]
        val danger = 1 + random.nextInt(10)
        val factions = factionPatterns[random.nextInt(factionPatterns.size)]
        val flags = buildSet {
            if (random.nextInt(5) == 0) add("ANCIENT_RUINS")
            if (danger >= 8) add("EXTREME_ROUTE")
            if (random.nextInt(7) == 0) add("RARE_RESOURCE")
        }
        return WorldIsland(
            id = islandId,
            name = "$prefix $suffix",
            climate = climate,
            danger = danger,
            factions = factions,
            flags = flags,
        )
    }

    private fun mixSeed(vararg parts: Any): Long {
        var hash = 0xCBF29CE484222325UL.toLong()
        parts.forEach { part ->
            part.toString().forEach { ch ->
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001B3L
            }
            hash = hash xor 0x9E3779B97F4A7C15UL.toLong()
        }
        return hash
    }
}
