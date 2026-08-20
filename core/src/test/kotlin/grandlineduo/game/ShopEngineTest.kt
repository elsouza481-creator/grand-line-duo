package grandlineduo.game

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ShopEngineTest {
    fun register() {
        test("buying available item charges berries and grants inventory") {
            val world = baseWorld(2_000)
            val bought = ShopEngine.buy(world, "p1", "bandage", 2)
            assertEquals(1_500L, bought.partyBerries)
            assertEquals(2, InventoryEngine.read(bought, "p1").items["bandage"])
        }

        test("selling item pays half value and removes it") {
            val world = InventoryEngine.grant(baseWorld(100), "p1", "energy_tonic", 1)
            val sold = ShopEngine.sell(world, "p1", "energy_tonic", 1)
            assertEquals(325L, sold.partyBerries)
            assertEquals(null, InventoryEngine.read(sold, "p1").items["energy_tonic"])
        }

        test("shop rejects unavailable route item") {
            var failed = false
            try { ShopEngine.buy(baseWorld(50_000), "p1", "kairouseki_shard", 1) } catch (_: IllegalArgumentException) { failed = true }
            assertTrue(failed)
        }
    }

    private fun baseWorld(berries: Long) = WorldState(
        campaignId = "shop",
        islandId = "stormglass-cay",
        partyBerries = berries,
        players = mapOf("p1" to PlayerState("p1", "A", 30, 30, 0), "p2" to PlayerState("p2", "B", 30, 30, 0)),
    )
}
