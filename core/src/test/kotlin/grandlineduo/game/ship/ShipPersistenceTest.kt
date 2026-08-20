package grandlineduo.game.ship

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.game.social.SocialState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object ShipPersistenceTest {
    fun register() {
        test("ship state round trips through current snapshot codec") {
            val ship = ShipEngine.applyUpgrade(
                ShipEngine.installCompartment(ShipEngine.starterShip("black-gull", "Black Gull"), ShipCompartment.KITCHEN),
                ShipUpgrade.SAILS,
            )
            val state = legacyWorld().copy(shipState = ship)
            assertEquals(state, WorldStateCodec.decode(WorldStateCodec.encode(state)))
        }

        test("campaign without ship preserves exact legacy canonical hash") {
            assertEquals(
                "ea73b0a8d4ca77206fce3925d537a8c8ae56cee64e5dc891ed1a41e469d82062",
                CanonicalStateHasher.hash(legacyWorld()),
            )
        }

        test("ship state participates in authoritative hash when present") {
            val base = legacyWorld()
            val withShip = base.copy(shipState = ShipEngine.starterShip("black-gull", "Black Gull"))
            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(withShip))
        }


        test("one-player-locked voyage state survives snapshot round trip") {
            val incident = VoyageIncident(VoyageIncidentType.STORM, 3, 4242)
            val active = VoyageEngine.lockAction(VoyageEncounter(incident), "p1", VoyageAction.HELM)
            val state = legacyWorld().copy(
                shipState = ShipEngine.starterShip("black-gull", "Black Gull"),
                activeVoyage = active,
            )
            val restored = WorldStateCodec.decode(WorldStateCodec.encode(state))
            assertEquals(active, restored.activeVoyage)
            assertEquals(state, restored)
        }

        test("version five snapshot decodes with no ship") {
            val restored = WorldStateCodec.decode(versionFiveBytes())
            assertEquals(null, restored.shipState)
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

    private fun versionFiveBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeInt(5)
            data.writeUTF("v5-no-ship")
            data.writeLong(3)
            data.writeUTF("stormglass-cay")
            data.writeLong(88)
            data.writeInt(0)
            data.writeInt(0) // faction standings
            data.writeInt(0) // npc relationships
            data.writeInt(1) // players
            data.writeUTF("p1")
            data.writeUTF("p1")
            data.writeUTF("Legacy")
            data.writeInt(20)
            data.writeInt(20)
            data.writeLong(900)
            data.writeInt(10)
            data.writeInt(10)
            data.writeBoolean(false)
            data.writeInt(0) // flags
        }
    }.toByteArray()
}
