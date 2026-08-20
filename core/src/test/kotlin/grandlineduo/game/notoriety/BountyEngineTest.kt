package grandlineduo.game.notoriety

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object BountyEngineTest {
    fun register() {
        test("secret incident does not automatically increase public bounty") {
            val result = BountyEngine.assess(
                currentBounty = 2_000_000,
                incident = BountyIncident(
                    BountyIncidentType.GOVERNMENT_SECRET_EXPOSED,
                    severity = 5,
                    visibility = IncidentVisibility.SECRET,
                ),
            )
            assertEquals(0L, result.delta)
            assertEquals(2_000_000L, result.newBounty)
            assertTrue(result.internalThreatPoints > 0)
        }

        test("rumored incident raises less bounty than confirmed incident") {
            val rumored = BountyEngine.assess(
                0,
                BountyIncident(BountyIncidentType.DEFEATED_MARINE_OFFICER, 2, IncidentVisibility.RUMORED),
            )
            val confirmed = BountyEngine.assess(
                0,
                BountyIncident(BountyIncidentType.DEFEATED_MARINE_OFFICER, 2, IncidentVisibility.CONFIRMED),
            )
            assertTrue(rumored.delta > 0)
            assertTrue(rumored.delta < confirmed.delta)
        }

        test("confirmed Marine base destruction has deterministic severity-scaled bounty") {
            val result = BountyEngine.assess(
                currentBounty = 2_000_000,
                incident = BountyIncident(
                    BountyIncidentType.MARINE_BASE_DESTROYED,
                    severity = 3,
                    visibility = IncidentVisibility.CONFIRMED,
                ),
            )
            assertEquals(12_000_000L, result.delta)
            assertEquals(14_000_000L, result.newBounty)
        }

        test("bounty is clamped to world maximum without overflow") {
            val result = BountyEngine.assess(
                currentBounty = 9_999_000_000,
                incident = BountyIncident(
                    BountyIncidentType.GOVERNMENT_SECRET_EXPOSED,
                    severity = 5,
                    visibility = IncidentVisibility.CONFIRMED,
                ),
            )
            assertEquals(10_000_000_000L, result.newBounty)
            assertEquals(1_000_000L, result.delta)
        }
    }
}
