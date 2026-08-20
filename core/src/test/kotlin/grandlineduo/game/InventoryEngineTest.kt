package grandlineduo.game

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object InventoryEngineTest {
    fun register() {
        test("inventory grants and equips a weapon deterministically") {
            val base = world()
            val granted = InventoryEngine.grant(base, "p1", "iron_sabre", 1)
            val equipped = InventoryEngine.equip(granted, "p1", "iron_sabre")
            val inv = InventoryEngine.read(equipped, "p1")
            assertEquals(1, inv.items.getValue("iron_sabre"))
            assertEquals("iron_sabre", inv.equipped[EquipmentSlot.WEAPON])
            assertEquals(4, InventoryEngine.combatBonus(equipped, "p1").attackDamage)
        }

        test("consumable heals without exceeding max hp and consumes one") {
            var state = world().copy(players = world().players + ("p1" to world().players.getValue("p1").copy(hp = 7)))
            state = InventoryEngine.grant(state, "p1", "bandage", 2)
            state = InventoryEngine.use(state, "p1", "bandage")
            assertEquals(20, state.players.getValue("p1").hp)
            assertEquals(1, InventoryEngine.read(state, "p1").items.getValue("bandage"))
        }

        test("equipped armor cannot be discarded until unequipped") {
            var state = InventoryEngine.grant(world(), "p1", "reinforced_coat", 1)
            state = InventoryEngine.equip(state, "p1", "reinforced_coat")
            var rejected = false
            try { InventoryEngine.discard(state, "p1", "reinforced_coat", 1) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            state = InventoryEngine.unequip(state, "p1", EquipmentSlot.ARMOR)
            state = InventoryEngine.discard(state, "p1", "reinforced_coat", 1)
            assertEquals(0, InventoryEngine.read(state, "p1").items["reinforced_coat"] ?: 0)
        }
    }

    private fun world(): WorldState = WorldState(
        campaignId = "inv-test",
        players = mapOf(
            "p1" to PlayerState("p1", "P1", 20, 20, 0, 10, 10),
            "p2" to PlayerState("p2", "P2", 20, 20, 0, 10, 10),
        ),
    )
}
