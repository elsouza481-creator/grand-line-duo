package grandlineduo.game.character

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object CharacterPersistenceTest {
    fun register() {
        test("character profile changes the canonical authoritative hash") {
            val profile = profile()
            val base = world(profile)
            val changed = world(profile.copy(dream = profile.dream + " e voltar vivo"))

            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(changed))
        }


        test("profileless state keeps legacy canonical hash for event-log compatibility") {
            val legacy = WorldState(
                campaignId = "legacy-hash",
                lastEventId = 7,
                islandId = "shells-town",
                partyBerries = 1200,
                players = mapOf("p1" to PlayerState("p1", "Kairo", 20, 20, 1000, 9, 10)),
                worldFlags = mapOf("marine_alert" to "2"),
            )
            assertEquals(
                "ea73b0a8d4ca77206fce3925d537a8c8ae56cee64e5dc891ed1a41e469d82062",
                CanonicalStateHasher.hash(legacy),
            )
        }

        test("world state codec v2 round trip preserves complete character profile") {
            val state = world(profile())
            assertEquals(state, WorldStateCodec.decode(WorldStateCodec.encode(state)))
        }

        test("world state codec still decodes version one snapshot without profile") {
            val restored = WorldStateCodec.decode(versionOneBytes())
            assertEquals("legacy-campaign", restored.campaignId)
            assertEquals("Legacy Kairo", restored.players.getValue("p1").name)
            assertEquals(null, restored.players.getValue("p1").profile)
            assertEquals(999L, restored.partyBerries)
        }
    }

    internal fun profile(): CharacterProfile {
        val result = CharacterCreation.create(CharacterCreationTest.validDraft())
        return (result as CharacterCreationResult.Success).profile.copy(
            evolutionPoints = 3,
            trainingMarks = setOf("attribute:VON", "skill:NAVIGATION"),
        )
    }

    private fun world(profile: CharacterProfile) = WorldState(
        campaignId = "profile-campaign",
        lastEventId = 9,
        islandId = "stormglass-cay",
        partyBerries = 4500,
        players = mapOf(
            "p1" to PlayerState(
                playerId = "p1",
                name = profile.name,
                hp = profile.maxHp - 3,
                maxHp = profile.maxHp,
                bounty = 1_200_000,
                energy = profile.maxEnergy - 2,
                maxEnergy = profile.maxEnergy,
                profile = profile,
            ),
        ),
        worldFlags = mapOf("manifest_revealed" to "true"),
    )

    private fun versionOneBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(1)
            data.writeUTF("legacy-campaign")
            data.writeLong(4)
            data.writeUTF("shells-town")
            data.writeLong(999)
            data.writeInt(1)
            data.writeUTF("p1")
            data.writeUTF("p1")
            data.writeUTF("Legacy Kairo")
            data.writeInt(18)
            data.writeInt(20)
            data.writeLong(1234)
            data.writeInt(8)
            data.writeInt(10)
            data.writeInt(1)
            data.writeUTF("legacy_flag")
            data.writeUTF("true")
        }
        return out.toByteArray()
    }
}
