package grandlineduo.game.arc

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object ArcPersistenceTest {
    fun register() {
        test("active narrative arc round trips through snapshot v8") {
            var arc = ArcEngine.start(
                ArcStartContext(55L, "ironwake-atoll", setOf("MARINES"), setOf("MARINE_RESPONSE_CAPTAIN"), 9_000_000L)
            )
            arc = ArcEngine.choose(arc, "p2", "shadow_authority").state
            val state = legacyWorld().copy(activeArc = arc)
            assertEquals(state, WorldStateCodec.decode(WorldStateCodec.encode(state)))
        }

        test("current snapshot preserves four-player narrative participants private clues and locked choices") {
            val participants = setOf("p1", "p2", "p3", "p4")
            var arc = ArcEngine.start(
                ArcStartContext(
                    seed = 88L,
                    islandId = "ironwake-atoll",
                    presentFactions = setOf("MARINES"),
                    worldFlags = setOf("MARINE_RESPONSE_CAPTAIN"),
                    totalBounty = 22_000_000L,
                    participantIds = participants,
                )
            )
            arc = ArcEngine.choose(arc, "p3", "shadow_authority").state
            arc = ArcEngine.choose(arc, "p4", "survey_route").state
            val state = legacyWorld().copy(activeArc = arc)

            assertEquals(state, WorldStateCodec.decode(WorldStateCodec.encode(state)))
        }

        test("campaign without active arc preserves exact legacy canonical hash") {
            assertEquals(
                "ea73b0a8d4ca77206fce3925d537a8c8ae56cee64e5dc891ed1a41e469d82062",
                CanonicalStateHasher.hash(legacyWorld()),
            )
        }

        test("active arc participates in authoritative hash") {
            val base = legacyWorld()
            val withArc = base.copy(
                activeArc = ArcEngine.start(ArcStartContext(1L, base.islandId, emptySet(), emptySet(), 0L))
            )
            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(withArc))
        }

        test("version seven snapshot decodes with no active arc") {
            val restored = WorldStateCodec.decode(versionSevenBytes())
            assertEquals(null, restored.activeArc)
            assertEquals("Legacy", restored.players.getValue("p1").name)
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

    private fun versionSevenBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeInt(7)
            data.writeUTF("v7-no-arc")
            data.writeLong(11)
            data.writeUTF("ironwake-atoll")
            data.writeLong(750)
            data.writeInt(0) // government threat
            data.writeInt(0) // faction standings
            data.writeInt(0) // npc relationships
            data.writeBoolean(false) // ship
            data.writeBoolean(false) // active voyage
            data.writeInt(0) // crew members
            data.writeInt(1) // players
            data.writeUTF("p1")
            data.writeUTF("p1")
            data.writeUTF("Legacy")
            data.writeInt(20)
            data.writeInt(20)
            data.writeLong(900)
            data.writeInt(10)
            data.writeInt(10)
            data.writeBoolean(false) // profile
            data.writeInt(0) // flags
        }
    }.toByteArray()
}