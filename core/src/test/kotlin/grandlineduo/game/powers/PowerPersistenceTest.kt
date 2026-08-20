package grandlineduo.game.powers

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.game.character.*
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object PowerPersistenceTest {
    fun register() {
        test("default no-power profile keeps exact v2 canonical hash") {
            val state = legacyHashState(baseProfile())
            assertEquals(
                "c83ae044e573402950419125e0e362eef9030bf51af35845576608dc03c27807",
                CanonicalStateHasher.hash(state),
            )
        }

        test("powered profile round trips through world state codec v3") {
            val powered = baseProfile().copy(
                haki = HakiState(
                    latentHaoshoku = true,
                    disciplines = mapOf(
                        HakiType.KENBUNSHOKU to HakiDiscipline(mastery = 2, useCount = 4),
                        HakiType.HAOSHOKU to HakiDiscipline(mastery = 1, useCount = 1),
                    ),
                ),
                devilFruit = DevilFruitState(
                    fruitId = "pulse-paramecia",
                    category = DevilFruitCategory.PARAMECIA,
                    revealedName = null,
                    mastery = 1,
                    useCount = 2,
                ),
            )
            val state = legacyHashState(powered)
            assertEquals(state, WorldStateCodec.decode(WorldStateCodec.encode(state)))
        }

        test("version two profile snapshot decodes with default empty power state") {
            val restored = WorldStateCodec.decode(versionTwoBytes())
            val profile = restored.players.getValue("p1").profile!!
            assertEquals(HakiState(), profile.haki)
            assertEquals(null, profile.devilFruit)
            assertEquals(3, profile.evolutionPoints)
            assertEquals(setOf("attribute:VON"), profile.trainingMarks)
        }

        test("Haki and Devil Fruit state participate in authoritative hash when present") {
            val base = legacyHashState(baseProfile())
            val withHaki = legacyHashState(
                baseProfile().copy(
                    haki = HakiState(
                        disciplines = mapOf(HakiType.BUSOSHOKU to HakiDiscipline(mastery = 1)),
                    ),
                )
            )
            val withFruit = legacyHashState(
                baseProfile().copy(
                    devilFruit = DevilFruitState(
                        fruitId = "pulse-paramecia",
                        category = DevilFruitCategory.PARAMECIA,
                    ),
                )
            )
            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(withHaki))
            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(withFruit))
            assertNotEquals(CanonicalStateHasher.hash(withHaki), CanonicalStateHasher.hash(withFruit))
        }
    }

    internal fun baseProfile() = CharacterProfile(
        name = "Kairo",
        age = 19,
        origin = "East Blue",
        appearance = "A",
        personality = "P",
        dream = "D",
        fear = "F",
        profession = "N",
        combatStyle = "S",
        background = "B",
        motivation = "M",
        pirateRelation = "PR",
        marineRelation = "MR",
        importantPerson = "I",
        defect = "X",
        attributes = mapOf(
            Attribute.FOR to 1,
            Attribute.DES to 2,
            Attribute.CON to 2,
            Attribute.INT to 1,
            Attribute.PER to 2,
            Attribute.CAR to 1,
            Attribute.VON to 1,
        ),
        skills = mapOf(Skill.NAVIGATION to 2, Skill.PERCEPTION to 2),
        evolutionPoints = 3,
        trainingMarks = setOf("attribute:VON"),
    )

    private fun legacyHashState(profile: CharacterProfile) = WorldState(
        campaignId = "legacy-profile-hash",
        lastEventId = 12,
        islandId = "stormglass-cay",
        partyBerries = 777,
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 27, 30, 1000, 15, 17, profile),
        ),
        worldFlags = mapOf("x" to "y"),
    )

    private fun versionTwoBytes(): ByteArray {
        val p = baseProfile()
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(2)
                data.writeUTF("v2-powerless")
                data.writeLong(4)
                data.writeUTF("stormglass-cay")
                data.writeLong(123)
                data.writeInt(1)
                data.writeUTF("p1")
                data.writeUTF("p1")
                data.writeUTF(p.name)
                data.writeInt(27)
                data.writeInt(30)
                data.writeLong(1000)
                data.writeInt(15)
                data.writeInt(17)
                data.writeBoolean(true)
                writeV2Profile(data, p)
                data.writeInt(1)
                data.writeUTF("legacy-v2")
                data.writeUTF("true")
            }
        }.toByteArray()
    }

    private fun writeV2Profile(data: DataOutputStream, p: CharacterProfile) {
        data.writeUTF(p.name)
        data.writeInt(p.age)
        data.writeUTF(p.origin)
        data.writeUTF(p.appearance)
        data.writeUTF(p.personality)
        data.writeUTF(p.dream)
        data.writeUTF(p.fear)
        data.writeUTF(p.profession)
        data.writeUTF(p.combatStyle)
        data.writeUTF(p.background)
        data.writeUTF(p.motivation)
        data.writeUTF(p.pirateRelation)
        data.writeUTF(p.marineRelation)
        data.writeUTF(p.importantPerson)
        data.writeUTF(p.defect)
        data.writeInt(p.evolutionPoints)
        val attrs = p.attributes.entries.sortedBy { it.key.ordinal }
        data.writeInt(attrs.size)
        attrs.forEach { (key, value) -> data.writeUTF(key.name); data.writeInt(value) }
        val skills = p.skills.entries.sortedBy { it.key.ordinal }
        data.writeInt(skills.size)
        skills.forEach { (key, value) -> data.writeUTF(key.name); data.writeInt(value) }
        val marks = p.trainingMarks.sorted()
        data.writeInt(marks.size)
        marks.forEach(data::writeUTF)
    }
}
