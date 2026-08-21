package grandlineduo.game.world

import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object GrandLineWorldAtlasTest {
    fun register() {
        test("starter island exposes three distinct Grand Line routes") {
            val routes = GrandLineWorldAtlas.availableDestinations(
                campaignId = "atlas-starter",
                currentIslandId = "stormglass-cay",
                voyageIndex = 0,
            )

            assertEquals(3, routes.size)
            assertEquals(listOf("emberwake", "brineveil", "gearfall"), routes.map { it.id })
            assertEquals(3, routes.map { it.id }.toSet().size)
            assertTrue(routes.all { it.danger in 1..10 })
        }

        test("procedural destinations are deterministic for the same campaign route and voyage") {
            val first = GrandLineWorldAtlas.availableDestinations("atlas-seed", "emberwake", 7)
            val second = GrandLineWorldAtlas.availableDestinations("atlas-seed", "emberwake", 7)

            assertEquals(first, second)
            assertEquals(3, first.map { it.id }.toSet().size)
        }

        test("different voyages keep extending the sea instead of exhausting a finite campaign list") {
            val early = GrandLineWorldAtlas.availableDestinations("atlas-endless", "emberwake", 5)
            val late = GrandLineWorldAtlas.availableDestinations("atlas-endless", early.first().id, 1000)

            assertEquals(3, late.size)
            assertTrue(late.all { it.id.isNotBlank() && it.name.isNotBlank() })
            assertNotEquals(early.map { it.id }.toSet(), late.map { it.id }.toSet())
        }

        test("generated island description is stable and contains playable world context") {
            val island = GrandLineWorldAtlas.availableDestinations("atlas-context", "gearfall", 22)[1]
            val described = GrandLineWorldAtlas.describe("atlas-context", island.id)

            assertEquals(island, described)
            assertTrue(described.climate.isNotBlank())
            assertTrue(described.danger in 1..10)
            assertTrue(described.factions.isNotEmpty())
        }
    }
}
