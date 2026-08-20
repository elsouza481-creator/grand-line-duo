package grandlineduo.game.notoriety

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test

object NotorietyPersistenceTest {
    fun register() {
        test("government threat points round trip through current snapshot codec") {
            val state = legacyWorld().copy(governmentThreatPoints = 37)
            assertEquals(state, WorldStateCodec.decode(WorldStateCodec.encode(state)))
        }

        test("zero government threat preserves exact legacy authoritative hash") {
            assertEquals(
                "ea73b0a8d4ca77206fce3925d537a8c8ae56cee64e5dc891ed1a41e469d82062",
                CanonicalStateHasher.hash(legacyWorld()),
            )
        }

        test("nonzero government threat participates in authoritative hash") {
            val base = legacyWorld()
            val threatened = base.copy(governmentThreatPoints = 1)
            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(threatened))
        }
    }

    private fun legacyWorld() = WorldState(
        campaignId = "legacy-hash",
        lastEventId = 7,
        islandId = "shells-town",
        partyBerries = 1200,
        players = mapOf("p1" to PlayerState("p1", "Kairo", 20, 20, 1000, 9, 10)),
        worldFlags = mapOf("marine_alert" to "2"),
    )
}
