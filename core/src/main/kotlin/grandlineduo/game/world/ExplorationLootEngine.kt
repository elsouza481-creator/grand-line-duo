package grandlineduo.game.world

import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine

/** Shared one-time world loot. Collection state is authoritative and global to the party. */
object ExplorationLootEngine {
    fun isCollected(world: WorldState, pickupId: String): Boolean =
        world.worldFlags[collectedKey(world.islandId, pickupId)] == "true"

    fun collect(world: WorldState, playerId: String, pickupId: String): WorldState {
        require(playerId in world.players) { "Unknown player $playerId" }
        require(pickupId.isNotBlank()) { "Pickup id is required" }
        require(!isCollected(world, pickupId)) { "Loot cache was already collected" }

        val pickup = ExplorationEngine.pickupAt(world, playerId)
            ?: throw IllegalArgumentException("Player must stand on the loot cache tile")
        require(pickup.id == pickupId) { "This tile does not contain $pickupId" }
        require(pickup.amount > 0 && pickup.berries >= 0L) { "Invalid pickup reward" }

        var next = world.copy(
            partyBerries = world.partyBerries + pickup.berries,
            worldFlags = world.worldFlags + (collectedKey(world.islandId, pickup.id) to "true"),
        )
        next = InventoryEngine.grant(next, playerId, pickup.itemId, pickup.amount)
        return next
    }

    private fun collectedKey(islandId: String, pickupId: String): String =
        "loot.$islandId.$pickupId.collected"
}
