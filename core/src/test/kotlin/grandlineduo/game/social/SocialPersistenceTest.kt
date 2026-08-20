package grandlineduo.game.social

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object SocialPersistenceTest {
    fun register() {
        test("social world memory round trips through current snapshot codec") {
            val social = SocialState(
                factionStanding = mapOf("MARINES" to -40, "ARASHI_KINGDOM" to 65),
                npcRelationships = mapOf(
                    "lyra" to NpcRelationship(70, NpcBond.ALLY, NpcStatus.ACTIVE),
                    "reno" to NpcRelationship(-80, NpcBond.ENEMY, NpcStatus.MISSING),
                ),
            )
            val state = legacyWorld().copy(socialState = social)
            assertEquals(state, WorldStateCodec.decode(WorldStateCodec.encode(state)))
        }

        test("empty social state preserves exact legacy canonical hash") {
            assertEquals(
                "ea73b0a8d4ca77206fce3925d537a8c8ae56cee64e5dc891ed1a41e469d82062",
                CanonicalStateHasher.hash(legacyWorld()),
            )
        }

        test("social state participates in authoritative hash when nonempty") {
            val base = legacyWorld()
            val changed = base.copy(
                socialState = SocialState(factionStanding = mapOf("MARINES" to -1)),
            )
            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(changed))
        }

        test("version four snapshot decodes with empty social memory") {
            val restored = WorldStateCodec.decode(versionFourBytes())
            assertEquals(27, restored.governmentThreatPoints)
            assertEquals(SocialState(), restored.socialState)
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

    private fun versionFourBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeInt(4)
            data.writeUTF("v4-social-empty")
            data.writeLong(2)
            data.writeUTF("stormglass-cay")
            data.writeLong(55)
            data.writeInt(27)
            data.writeInt(1)
            data.writeUTF("p1")
            data.writeUTF("p1")
            data.writeUTF("Legacy")
            data.writeInt(20)
            data.writeInt(20)
            data.writeLong(900)
            data.writeInt(10)
            data.writeInt(10)
            data.writeBoolean(false)
            data.writeInt(0)
        }
    }.toByteArray()
}
