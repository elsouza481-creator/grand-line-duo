package grandlineduo.game.character

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object ClassMasteryPersistenceTest {
    fun register() {
        test("character creation starts mastery for the chosen class path") {
            val result = CharacterCreation.create(
                CharacterCreationTest.validDraft().copy(classPath = ClassPath.NAVIGATOR),
            )
            assertTrue(result is CharacterCreationResult.Success)
            val profile = (result as CharacterCreationResult.Success).profile

            assertEquals(ClassPath.NAVIGATOR, profile.classMastery?.primaryClass)
            assertEquals(0, profile.classMastery?.levelOf(ClassPath.NAVIGATOR))
        }

        test("world state codec round trip preserves class mastery progression") {
            val base = (CharacterCreation.create(
                CharacterCreationTest.validDraft().copy(classPath = ClassPath.SWORDSMAN),
            ) as CharacterCreationResult.Success).profile
            val mastery = ClassMasteryEngine.train(
                base.classMastery ?: error("missing class mastery"),
                ClassPath.SWORDSMAN,
                240,
            )
            val profile = base.copy(classMastery = mastery)
            val state = world(profile)

            val restored = WorldStateCodec.decode(WorldStateCodec.encode(state))
            val restoredMastery = restored.players.getValue("p1").profile?.classMastery

            assertEquals(mastery, restoredMastery)
            assertEquals(2, restoredMastery?.levelOf(ClassPath.SWORDSMAN))
            assertEquals(15L, restoredMastery?.experienceOf(ClassPath.SWORDSMAN))
        }

        test("class mastery progression changes the canonical authoritative hash") {
            val base = (CharacterCreation.create(
                CharacterCreationTest.validDraft().copy(classPath = ClassPath.BRAWLER),
            ) as CharacterCreationResult.Success).profile
            val trained = base.copy(
                classMastery = ClassMasteryEngine.train(
                    base.classMastery ?: error("missing class mastery"),
                    ClassPath.BRAWLER,
                    100,
                ),
            )

            assertNotEquals(
                CanonicalStateHasher.hash(world(base)),
                CanonicalStateHasher.hash(world(trained)),
            )
        }

        test("version nine snapshot with a profile still decodes without class mastery") {
            val restored = WorldStateCodec.decode(versionNineBytes())
            val profile = restored.players.getValue("p1").profile

            assertEquals("Legacy Kairo", profile?.name)
            assertEquals(null, profile?.classMastery)
        }
    }

    private fun world(profile: CharacterProfile) = WorldState(
        campaignId = "class-mastery-campaign",
        lastEventId = 11,
        islandId = "stormglass-cay",
        partyBerries = 5500,
        players = mapOf(
            "p1" to PlayerState(
                playerId = "p1",
                name = profile.name,
                hp = profile.maxHp,
                maxHp = profile.maxHp,
                bounty = 250_000,
                energy = profile.maxEnergy,
                maxEnergy = profile.maxEnergy,
                profile = profile,
            ),
        ),
    )

    private fun versionNineBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(9)
            data.writeUTF("legacy-profile-v9")
            data.writeLong(3)
            data.writeUTF("shells-town")
            data.writeLong(900)
            data.writeInt(0)

            data.writeInt(0)
            data.writeInt(0)
            data.writeBoolean(false)
            data.writeBoolean(false)
            data.writeInt(0)
            data.writeBoolean(false)
            data.writeBoolean(false)

            data.writeInt(1)
            data.writeUTF("p1")
            data.writeUTF("p1")
            data.writeUTF("Legacy Kairo")
            data.writeInt(20)
            data.writeInt(20)
            data.writeLong(0)
            data.writeInt(10)
            data.writeInt(10)
            data.writeBoolean(true)

            data.writeUTF("Legacy Kairo")
            data.writeInt(19)
            data.writeUTF("East Blue")
            data.writeUTF("Casaco antigo")
            data.writeUTF("Leal")
            data.writeUTF("Cruzar a Grand Line")
            data.writeUTF("Perder a tripulacao")
            data.writeUTF("Navegador")
            data.writeUTF("Espada")
            data.writeUTF("Porto antigo")
            data.writeUTF("Liberdade")
            data.writeUTF("Neutro")
            data.writeUTF("Desconfiado")
            data.writeUTF("Orin")
            data.writeUTF("Impulsivo")
            data.writeInt(2)

            data.writeInt(Attribute.entries.size)
            Attribute.entries.forEach { attribute ->
                data.writeUTF(attribute.name)
                data.writeInt(if (attribute == Attribute.CON) 2 else 1)
            }
            data.writeInt(0)
            data.writeInt(0)

            data.writeBoolean(false)
            data.writeInt(0)
            data.writeBoolean(false)

            data.writeInt(0)
        }
        return out.toByteArray()
    }
}
