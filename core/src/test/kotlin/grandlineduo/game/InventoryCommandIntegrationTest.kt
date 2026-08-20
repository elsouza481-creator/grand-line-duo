package grandlineduo.game

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object InventoryCommandIntegrationTest {
    fun register() {
        test("host authoritative inventory command equips owned item") {
            var state = baseWorld()
            state = InventoryEngine.grant(state, "p2", "iron_sabre", 1)
            val host = HostReplica(state)
            val handler = StormglassGameplayCommandHandler(host, 11L)
            handler.handle(GameplayWireCommand.InventoryAction("inv-1", "p2", "EQUIP", "iron_sabre"), 1L)
            assertEquals("iron_sabre", InventoryEngine.read(host.state, "p2").equipped[EquipmentSlot.WEAPON])
        }

        test("inventory retry is idempotent") {
            var state = baseWorld()
            state = InventoryEngine.grant(state, "p1", "bandage", 2)
            val host = HostReplica(state)
            val handler = StormglassGameplayCommandHandler(host, 11L)
            val cmd = GameplayWireCommand.InventoryAction("inv-use", "p1", "USE", "bandage")
            handler.handle(cmd, 1L)
            handler.handle(cmd, 2L)
            assertEquals(1, InventoryEngine.read(host.state, "p1").items.getValue("bandage"))
        }
    }

    private fun baseWorld() = WorldState(
        campaignId = "inv-command",
        players = mapOf(
            "p1" to PlayerState("p1", "P1", 10, 20, 0, 10, 10),
            "p2" to PlayerState("p2", "P2", 20, 20, 0, 10, 10),
        ),
    )
}
