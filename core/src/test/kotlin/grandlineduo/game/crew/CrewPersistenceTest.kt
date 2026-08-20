package grandlineduo.game.crew

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object CrewPersistenceTest {
    fun register() {
        test("living crew round trips through snapshot v7") {
            val crew = CrewState(
                mapOf(
                    "mira" to CrewMemberState(
                        npcId = "mira",
                        name = "Mira",
                        role = CrewRole.NAVIGATOR,
                        competence = 4,
                        loyalty = 65,
                        injurySeverity = 1,
                        status = CrewStatus.WOUNDED,
                        playerAffinity = mapOf("p1" to 40, "p2" to -10),
                    ),
                    "brock" to CrewMemberState(
                        npcId = "brock",
                        name = "Brock",
                        role = CrewRole.CARPENTER,
                        competence = 3,
                        loyalty = 10,
                        status = CrewStatus.CAPTURED,
                    ),
                ),
            )
            val state = legacyWorld().copy(crewState = crew)
            assertEquals(state, WorldStateCodec.decode(WorldStateCodec.encode(state)))
        }

        test("empty crew preserves exact legacy canonical hash") {
            assertEquals(
                "ea73b0a8d4ca77206fce3925d537a8c8ae56cee64e5dc891ed1a41e469d82062",
                CanonicalStateHasher.hash(legacyWorld()),
            )
        }

        test("crew state participates in authoritative hash when populated") {
            val base = legacyWorld()
            val withCrew = base.copy(
                crewState = CrewState(
                    mapOf("mira" to CrewMemberState("mira", "Mira", CrewRole.NAVIGATOR, 4, loyalty = 30)),
                ),
            )
            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(withCrew))
        }

        test("version six snapshot decodes with empty crew") {
            val restored = WorldStateCodec.decode(versionSixBytes())
            assertEquals(CrewState(), restored.crewState)
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

    private fun versionSixBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeInt(6)
            data.writeUTF("v6-no-crew")
            data.writeLong(9)
            data.writeUTF("open-sea")
            data.writeLong(500)
            data.writeInt(0)
            data.writeInt(0) // faction standings
            data.writeInt(0) // npc relationships
            data.writeBoolean(false) // ship
            data.writeBoolean(false) // active voyage
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
