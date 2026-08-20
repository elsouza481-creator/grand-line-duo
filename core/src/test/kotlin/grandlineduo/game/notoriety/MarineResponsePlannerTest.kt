package grandlineduo.game.notoriety

import grandlineduo.game.powers.DevilFruitCategory
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object MarineResponsePlannerTest {
    fun register() {
        test("Marine response remains unavailable when Marines cannot reach the island") {
            val plan = MarineResponsePlanner.plan(
                MarineResponseContext(
                    totalBounty = 80_000_000,
                    internalThreatPoints = 40,
                    marinesCanReach = false,
                    exposedHaoshoku = true,
                    exposedDevilFruitCategory = DevilFruitCategory.LOGIA,
                )
            )
            assertEquals(MarineResponseTier.NONE, plan.tier)
            assertEquals(emptySet<String>(), plan.directorFlags)
        }

        test("ordinary low-bounty pirates draw only a patrol") {
            val plan = MarineResponsePlanner.plan(
                MarineResponseContext(
                    totalBounty = 3_000_000,
                    internalThreatPoints = 0,
                    marinesCanReach = true,
                )
            )
            assertEquals(MarineResponseTier.PATROL, plan.tier)
            assertTrue("MARINE_RESPONSE_PATROL" in plan.directorFlags)
        }

        test("confirmed high bounty plus exposed Logia escalates to specialist response") {
            val plan = MarineResponsePlanner.plan(
                MarineResponseContext(
                    totalBounty = 70_000_000,
                    internalThreatPoints = 24,
                    marinesCanReach = true,
                    exposedDevilFruitCategory = DevilFruitCategory.LOGIA,
                )
            )
            assertEquals(MarineResponseTier.SPECIALIST, plan.tier)
            assertTrue("MARINE_RESPONSE_SPECIALIST" in plan.directorFlags)
        }

        test("Haoshoku exposure plus extreme notoriety can justify vice admiral attention") {
            val plan = MarineResponsePlanner.plan(
                MarineResponseContext(
                    totalBounty = 180_000_000,
                    internalThreatPoints = 50,
                    marinesCanReach = true,
                    exposedHaoshoku = true,
                )
            )
            assertEquals(MarineResponseTier.VICE_ADMIRAL, plan.tier)
            assertTrue("MARINE_RESPONSE_VICE_ADMIRAL" in plan.directorFlags)
        }
    }
}
