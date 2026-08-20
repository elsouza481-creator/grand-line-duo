package grandlineduo.game.arc

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.game.combat.*
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object ArcCombatPersistenceTest {
    fun register() {
        test("active combat round trips through snapshot v9") {
            val state = legacyWorld().copy(activeCombat = combat())
            assertEquals(state, WorldStateCodec.decode(WorldStateCodec.encode(state)))
        }

        test("campaign without active combat preserves exact legacy canonical hash") {
            assertEquals(
                "ea73b0a8d4ca77206fce3925d537a8c8ae56cee64e5dc891ed1a41e469d82062",
                CanonicalStateHasher.hash(legacyWorld()),
            )
        }

        test("active combat participates in authoritative hash") {
            val base = legacyWorld()
            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(base.copy(activeCombat = combat())))
        }

        test("version eight snapshot decodes with no active combat") {
            val restored = WorldStateCodec.decode(versionEightBytes())
            assertEquals(null, restored.activeCombat)
            assertEquals("Legacy", restored.players.getValue("p1").name)
        }
    }

    private fun combat() = CombatState(
        round = 2,
        players = mapOf(
            "p1" to Combatant("p1", "Kairo", 18, 20),
            "p2" to Combatant("p2", "Namiya", 20, 20),
        ),
        enemy = EnemyCombatant("boss", "Commander Rook", 74, 120, 18),
        telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
        lockedActions = mapOf("p2" to CombatAction("p2", CombatActionType.SETUP)),
    )

    private fun legacyWorld() = WorldState(
        campaignId = "legacy-hash",
        lastEventId = 7,
        islandId = "shells-town",
        partyBerries = 1200,
        players = mapOf("p1" to PlayerState("p1", "Kairo", 20, 20, 1000, 9, 10)),
        worldFlags = mapOf("marine_alert" to "2"),
    )

    private fun versionEightBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeInt(8)
            data.writeUTF("v8-no-combat")
            data.writeLong(4)
            data.writeUTF("ironwake-atoll")
            data.writeLong(100)
            data.writeInt(0)
            data.writeInt(0) // faction standings
            data.writeInt(0) // npc relationships
            data.writeBoolean(false) // ship
            data.writeBoolean(false) // voyage
            data.writeInt(0) // crew
            data.writeBoolean(false) // active arc
            data.writeInt(1) // players
            data.writeUTF("p1")
            data.writeUTF("p1")
            data.writeUTF("Legacy")
            data.writeInt(20)
            data.writeInt(20)
            data.writeLong(0)
            data.writeInt(10)
            data.writeInt(10)
            data.writeBoolean(false)
            data.writeInt(0) // flags
        }
    }.toByteArray()
}
