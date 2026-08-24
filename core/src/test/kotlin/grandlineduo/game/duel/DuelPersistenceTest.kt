package grandlineduo.game.duel

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.game.combat.CombatAction
import grandlineduo.game.combat.CombatActionType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test
import java.util.Base64

object DuelPersistenceTest {
    fun register() {
        test("pending duel round trips through snapshot v11") {
            val duel = DuelState(
                duelId = "duel-pending",
                challengerId = "p2",
                challengedId = "p1",
                phase = DuelPhase.PENDING,
            )
            val world = sampleWorld().copy(activeDuel = duel)
            assertEquals(world, WorldStateCodec.decode(WorldStateCodec.encode(world)))
        }

        test("active duel with locked action and setup state round trips through snapshot v11") {
            val duel = activeDuel().copy(
                lockedActions = mapOf("p1" to CombatAction("p1", CombatActionType.FINISHER)),
                setupReady = setOf("p1"),
            )
            val world = sampleWorld().copy(activeDuel = duel)
            assertEquals(world, WorldStateCodec.decode(WorldStateCodec.encode(world)))
        }

        test("finished knockout duel round trips through snapshot v11") {
            val duel = activeDuel().copy(
                phase = DuelPhase.FINISHED,
                fighters = mapOf(
                    "p1" to DuelFighter("p1", "Kairo", 33, 60),
                    "p2" to DuelFighter("p2", "Namiya", 1, 55),
                ),
                winnerId = "p1",
                loserId = "p2",
                finishReason = DuelFinishReason.KNOCKOUT,
            )
            val world = sampleWorld().copy(activeDuel = duel)
            assertEquals(world, WorldStateCodec.decode(WorldStateCodec.encode(world)))
        }

        test("finished double knockout duel round trips through snapshot v11") {
            val duel = activeDuel().copy(
                phase = DuelPhase.FINISHED,
                fighters = mapOf(
                    "p1" to DuelFighter("p1", "Kairo", 1, 60),
                    "p2" to DuelFighter("p2", "Namiya", 1, 55),
                ),
                winnerId = null,
                loserId = null,
                finishReason = DuelFinishReason.DOUBLE_KNOCKOUT,
            )
            val world = sampleWorld().copy(activeDuel = duel)
            assertEquals(world, WorldStateCodec.decode(WorldStateCodec.encode(world)))
        }

        test("version ten snapshot decodes with no duel state") {
            val legacyV10 = Base64.getDecoder().decode(
                "AAAACgAKbGVnYWN5LXYxMAAAAAAAAAAHAAZvcmlnaW4AAAAAAAAAewAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
            )
            val decoded = WorldStateCodec.decode(legacyV10)
            assertEquals("legacy-v10", decoded.campaignId)
            assertEquals(7L, decoded.lastEventId)
            assertEquals(123L, decoded.partyBerries)
            assertEquals(null, decoded.activeDuel)
        }

        test("null duel preserves the existing canonical hash while a duel changes it") {
            val world = sampleWorld()
            assertEquals(CanonicalStateHasher.hash(world), CanonicalStateHasher.hash(world.copy(activeDuel = null)))
            assertNotEquals(
                CanonicalStateHasher.hash(world),
                CanonicalStateHasher.hash(world.copy(activeDuel = activeDuel())),
            )
        }

        test("duel canonical hash is stable regardless of map insertion order") {
            val actionsA = linkedMapOf(
                "p1" to CombatAction("p1", CombatActionType.ATTACK),
                "p2" to CombatAction("p2", CombatActionType.DEFEND),
            )
            val actionsB = linkedMapOf(
                "p2" to CombatAction("p2", CombatActionType.DEFEND),
                "p1" to CombatAction("p1", CombatActionType.ATTACK),
            )
            val fightersA = linkedMapOf(
                "p1" to DuelFighter("p1", "Kairo", 40, 60),
                "p2" to DuelFighter("p2", "Namiya", 30, 55),
            )
            val fightersB = linkedMapOf(
                "p2" to DuelFighter("p2", "Namiya", 30, 55),
                "p1" to DuelFighter("p1", "Kairo", 40, 60),
            )
            val a = activeDuel().copy(fighters = fightersA, lockedActions = actionsA, setupReady = linkedSetOf("p2", "p1"))
            val b = activeDuel().copy(fighters = fightersB, lockedActions = actionsB, setupReady = linkedSetOf("p1", "p2"))
            assertEquals(
                CanonicalStateHasher.hash(sampleWorld().copy(activeDuel = a)),
                CanonicalStateHasher.hash(sampleWorld().copy(activeDuel = b)),
            )
        }
    }

    private fun sampleWorld() = WorldState(
        campaignId = "duel-save",
        lastEventId = 9,
        islandId = "ironwake",
        partyBerries = 456,
    )

    private fun activeDuel() = DuelState(
        duelId = "duel-active",
        challengerId = "p1",
        challengedId = "p2",
        phase = DuelPhase.ACTIVE,
        round = 3,
        fighters = mapOf(
            "p1" to DuelFighter("p1", "Kairo", 40, 60),
            "p2" to DuelFighter("p2", "Namiya", 30, 55),
        ),
    )
}
