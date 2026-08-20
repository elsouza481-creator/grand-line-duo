package grandlineduo.core.hash

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test

object CanonicalStateHasherTest {
    fun register() {
        test("canonical hash is stable for equivalent world states") {
            val first = WorldState(
                campaignId = "campaign-1",
                lastEventId = 7,
                islandId = "dawn-island",
                partyBerries = 1200,
                players = mapOf(
                    "p2" to PlayerState("p2", "Namiya", hp = 18, maxHp = 20, bounty = 500),
                    "p1" to PlayerState("p1", "Kairo", hp = 20, maxHp = 20, bounty = 1000),
                ),
                worldFlags = mapOf("saved_village" to "true", "marine_alert" to "2"),
            )
            val sameLogicalStateDifferentMapOrder = WorldState(
                campaignId = "campaign-1",
                lastEventId = 7,
                islandId = "dawn-island",
                partyBerries = 1200,
                players = linkedMapOf(
                    "p1" to PlayerState("p1", "Kairo", hp = 20, maxHp = 20, bounty = 1000),
                    "p2" to PlayerState("p2", "Namiya", hp = 18, maxHp = 20, bounty = 500),
                ),
                worldFlags = linkedMapOf("marine_alert" to "2", "saved_village" to "true"),
            )

            assertEquals(
                CanonicalStateHasher.hash(first),
                CanonicalStateHasher.hash(sameLogicalStateDifferentMapOrder),
            )
        }

        test("canonical hash changes when persistent state changes") {
            val base = WorldState(
                campaignId = "campaign-1",
                lastEventId = 7,
                islandId = "dawn-island",
                partyBerries = 1200,
                players = mapOf("p1" to PlayerState("p1", "Kairo", 20, 20, 1000)),
            )
            val changed = base.copy(partyBerries = 1201)

            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(changed))
        }
    }
}
