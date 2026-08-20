package grandlineduo.game.arc

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.ship.ShipDirectorBridge
import grandlineduo.game.ship.ShipEngine
import grandlineduo.game.social.NpcBond
import grandlineduo.game.social.NpcRelationship
import grandlineduo.game.social.SocialState
import grandlineduo.game.social.SocialWorldFlags
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ArcDirectorBridgeTest {
    fun register() {
        test("Marine presence and response intelligence justify a Marine occupation arc") {
            val world = baseWorld().copy(worldFlags = mapOf("MARINE_RESPONSE_CAPTAIN" to "true"))
            val context = ArcDirectorBridge.contextFor(world, 41L, setOf("MARINES"))
            assertTrue("MARINE_RESPONSE_CAPTAIN" in context.worldFlags)
            assertEquals(ArcArchetype.MARINE_OCCUPATION, ArcEngine.start(context).archetype)
        }

        test("active ally becomes a contextual resistance contact hook") {
            val world = baseWorld().copy(
                socialState = SocialState(
                    npcRelationships = mapOf("dockworker" to NpcRelationship(72, NpcBond.ALLY))
                )
            )
            val context = ArcDirectorBridge.contextFor(world, 7L, setOf("CIVILIANS"))
            assertTrue(SocialWorldFlags.HAS_ALLY in context.worldFlags)
            assertTrue(ArcDirectorBridge.ALLIED_CONTACT_AVAILABLE in context.worldFlags)
        }

        test("low ship supplies become a relief complication flag") {
            val ship = ShipEngine.starterShip("starter", "Going Merryish").copy(supplies = 1, maxSupplies = 20)
            val context = ArcDirectorBridge.contextFor(baseWorld().copy(shipState = ship), 8L, emptySet())
            assertTrue(ShipDirectorBridge.SHIP_LOW_SUPPLIES in context.worldFlags)
            assertTrue(ArcDirectorBridge.RESOURCE_PRESSURE in context.worldFlags)
        }

        test("same world seed and factions produce identical arc context") {
            val world = baseWorld().copy(worldFlags = mapOf("ANCIENT_RUINS" to "true"))
            assertEquals(
                ArcDirectorBridge.contextFor(world, 999L, setOf("ARCHAEOLOGISTS")),
                ArcDirectorBridge.contextFor(world, 999L, setOf("ARCHAEOLOGISTS")),
            )
        }
    }

    private fun baseWorld() = WorldState(
        campaignId = "arc-bridge",
        islandId = "ironwake-atoll",
        players = mapOf(
            "p1" to PlayerState("p1", "A", 30, 30, 4_000_000L),
            "p2" to PlayerState("p2", "B", 30, 30, 6_000_000L),
        ),
    )
}
