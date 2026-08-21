package grandlineduo.game.arc

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.CombatStatus
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ArcBossFactoryTest {
    fun register() {
        test("each arc archetype creates a distinct justified boss identity") {
            val identities = ArcArchetype.entries.associateWith { archetype ->
                ArcBossFactory.create(world(), arc(archetype, 2)).enemy.id
            }
            assertEquals(ArcArchetype.entries.size, identities.values.toSet().size)
        }

        test("same world and arc create identical boss combat state") {
            val arc = arc(ArcArchetype.MARINE_OCCUPATION, 3)
            assertEquals(ArcBossFactory.create(world(), arc), ArcBossFactory.create(world(), arc))
        }

        test("higher escalation increases boss pressure but remains bounded") {
            val low = ArcBossFactory.create(world(), arc(ArcArchetype.PIRATE_TYRANNY, 0)).enemy
            val high = ArcBossFactory.create(world(), arc(ArcArchetype.PIRATE_TYRANNY, 10)).enemy
            assertTrue(high.maxHp > low.maxHp)
            assertTrue(high.attackPower > low.attackPower)
            assertTrue(high.maxHp <= 220)
            assertTrue(high.attackPower <= 28)
        }

        test("boss combat carries current party health without healing players") {
            val combat = ArcBossFactory.create(world(), arc(ArcArchetype.UNDERWORLD_SMUGGLING, 1))
            assertEquals(17, combat.players.getValue("p1").hp)
            assertEquals(13, combat.players.getValue("p2").hp)
            assertEquals(CombatStatus.ACTIVE, combat.status)
            assertTrue(combat.telegraph.targetPlayerId in setOf("p1", "p2"))
        }

        test("arc boss includes every created human player in a four player co-op party") {
            val fourPlayerWorld = world().copy(
                players = world().players + mapOf(
                    "p3" to PlayerState("p3", "Rika", 21, 32, 4_000_000),
                    "p4" to PlayerState("p4", "Bram", 19, 35, 3_000_000),
                ),
            )
            val combat = ArcBossFactory.create(fourPlayerWorld, arc(ArcArchetype.RUINS_MYSTERY, 4))

            assertEquals(setOf("p1", "p2", "p3", "p4"), combat.players.keys)
            assertEquals(21, combat.players.getValue("p3").hp)
            assertEquals(19, combat.players.getValue("p4").hp)
            assertTrue(combat.telegraph.targetPlayerId in combat.players.keys)
        }

        test("different arc seed can change initial telegraph without changing boss rules") {
            val a = ArcBossFactory.create(world(), arc(ArcArchetype.MARINE_OCCUPATION, 2, seed = 11L))
            val b = ArcBossFactory.create(world(), arc(ArcArchetype.MARINE_OCCUPATION, 2, seed = 12L))
            assertEquals(a.enemy, b.enemy)
            assertNotEquals(ArcBossFactory.combatSeed(arc(ArcArchetype.MARINE_OCCUPATION, 2, 11L)), ArcBossFactory.combatSeed(arc(ArcArchetype.MARINE_OCCUPATION, 2, 12L)))
        }
    }

    private fun world() = WorldState(
        campaignId = "boss-factory",
        islandId = "ironwake-atoll",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 17, 30, 9_000_000),
            "p2" to PlayerState("p2", "Namiya", 13, 28, 8_000_000),
        ),
    )

    private fun arc(archetype: ArcArchetype, escalation: Int, seed: Long = 77L) = ArcState(
        arcId = "ironwake:${archetype.name}:$seed",
        islandId = "ironwake-atoll",
        seed = seed,
        archetype = archetype,
        phase = ArcPhase.AFTERMATH,
        escalation = escalation,
    )
}
