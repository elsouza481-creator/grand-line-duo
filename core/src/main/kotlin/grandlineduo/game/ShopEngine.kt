package grandlineduo.game

import grandlineduo.core.model.WorldState

object ShopEngine {
    fun stockFor(islandId: String): List<ItemDefinition> {
        val ids = when (islandId) {
            "stormglass-cay" -> listOf("bandage", "ration", "energy_tonic", "rusted_cutlass", "reinforced_coat", "lucky_charm")
            "emberwake" -> listOf("bandage", "ration", "energy_tonic", "iron_sabre", "reinforced_coat")
            "brineveil" -> listOf("bandage", "energy_tonic", "flintlock", "marine_vest")
            "gearfall" -> listOf("ration", "energy_tonic", "iron_sabre", "flintlock", "lucky_charm")
            "hollow-crown" -> listOf("bandage", "ration", "energy_tonic", "reinforced_coat")
            "meridian-vault" -> listOf("bandage", "ration", "energy_tonic", "iron_sabre", "marine_vest")
            else -> listOf("bandage", "ration", "energy_tonic")
        }
        return ids.map(ItemCatalog::get)
    }

    fun buy(world: WorldState, playerId: String, itemId: String, amount: Int): WorldState {
        require(amount > 0) { "Amount must be positive" }
        require(stockFor(world.islandId).any { it.id == itemId }) { "Item is not sold on this island" }
        val item = ItemCatalog.get(itemId)
        require(item.valueBerries > 0) { "Item cannot be purchased" }
        val cost = item.valueBerries * amount
        require(world.partyBerries >= cost) { "Insufficient Berries" }
        return InventoryEngine.grant(world.copy(partyBerries = world.partyBerries - cost), playerId, itemId, amount)
    }

    fun sell(world: WorldState, playerId: String, itemId: String, amount: Int): WorldState {
        require(amount > 0) { "Amount must be positive" }
        val item = ItemCatalog.get(itemId)
        require(item.valueBerries > 0 && item.type != ItemType.KEY) { "Item cannot be sold" }
        val afterRemoval = InventoryEngine.discard(world, playerId, itemId, amount)
        val payout = (item.valueBerries / 2L) * amount
        return afterRemoval.copy(partyBerries = afterRemoval.partyBerries + payout)
    }
}
