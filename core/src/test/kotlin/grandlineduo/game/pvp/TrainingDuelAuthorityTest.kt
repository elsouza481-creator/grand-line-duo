package grandlineduo.game.pvp

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object TrainingDuelAuthorityTest {
    fun register() {
        test("pending duel blocks unrelated world actions and challenger can cancel") {
            val host = preparedHost("duel-authority-pending")
            val handler = StormglassGameplayCommandHandler(host, seed = 91L)
            handler.handle(
                GameplayWireCommand.WorldAction("challenge", "p1", "DUEL_CHALLENGE"),
                1_000,
            )
            val pendingHash = CanonicalStateHasher.hash(host.state)

            val blocked = captureIllegalArgument {
                handler.handle(
                    GameplayWireCommand.WorldAction("shop-during-challenge", "p1", "SHOP_BUY", "bandage"),
                    1_001,
                )
            }
            assertTrue("duel" in blocked.lowercase())
            assertEquals(pendingHash, CanonicalStateHasher.hash(host.state))

            handler.handle(
                GameplayWireCommand.WorldAction("cancel", "p1", "DUEL_CANCEL"),
                1_002,
            )
            assertEquals(null, TrainingDuelEngine.state(host.state))
        }

        test("active duel rejects inventory and non duel world actions without mutation") {
            val host = preparedHost("duel-authority-active")
            val handler = StormglassGameplayCommandHandler(host, seed = 92L)
            handler.handle(
                GameplayWireCommand.WorldAction("challenge", "p1", "DUEL_CHALLENGE"),
                2_000,
            )
            handler.handle(
                GameplayWireCommand.WorldAction("accept", "p2", "DUEL_ACCEPT"),
                2_001,
            )
            val activeHash = CanonicalStateHasher.hash(host.state)

            val inventoryBlocked = captureIllegalArgument {
                handler.handle(
                    GameplayWireCommand.InventoryAction("inventory", "p1", "USE", "bandage"),
                    2_002,
                )
            }
            assertTrue("duel" in inventoryBlocked.lowercase())
            assertEquals(activeHash, CanonicalStateHasher.hash(host.state))

            val trainingBlocked = captureIllegalArgument {
                handler.handle(
                    GameplayWireCommand.WorldAction("training", "p1", "TRAIN_CLASS", "SWORDSMAN"),
                    2_003,
                )
            }
            assertTrue("duel" in trainingBlocked.lowercase())
            assertEquals(activeHash, CanonicalStateHasher.hash(host.state))
        }
    }

    private fun preparedHost(id: String): HostReplica {
        var world = WorldState(
            campaignId = id,
            islandId = "stormglass-cay",
            players = mapOf(
                "p1" to PlayerState("p1", "Kairo", 30, 30, 0),
                "p2" to PlayerState("p2", "Namiya", 24, 24, 0),
            ),
        )
        val training = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            .interactions.entries.single { it.value == ExplorationInteraction.TRAINING }.key
        world = ExplorationEngine.place(world, "p1", training)
        world = ExplorationEngine.place(world, "p2", training)
        return HostReplica(world)
    }

    private fun captureIllegalArgument(block: () -> Unit): String {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e.message ?: ""
        }
        error("Expected IllegalArgumentException")
    }
}
