package grandlineduo.game.world

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationFieldBossTest {
    fun register() {
        test("safe starter island keeps only common free roam enemies") {
            val map = ExplorationEngine.mapFor("field-boss-low", "stormglass-cay")

            assertEquals(3, map.enemies.size)
            assertTrue(map.enemies.values.all { it.rank == ExplorationEnemyRank.COMMON })
        }

        test("danger six or higher adds one deterministic optional physical field boss") {
            val a = ExplorationEngine.mapFor("field-boss-high", "meridian-vault")
            val b = ExplorationEngine.mapFor("field-boss-high", "meridian-vault")

            assertEquals(a.enemies, b.enemies)
            assertEquals(4, a.enemies.size)
            val boss = a.enemies.values.single { it.rank == ExplorationEnemyRank.FIELD_BOSS }
            val commons = a.enemies.values.filter { it.rank == ExplorationEnemyRank.COMMON }
            val dock = a.interactions.entries.first { it.value == ExplorationInteraction.DOCK }.key

            assertEquals(3, commons.size)
            assertTrue(a.isWalkable(boss.position))
            assertTrue(boss.position != a.spawn)
            assertTrue(boss.position !in a.interactions)
            assertTrue(boss.position !in a.npcs)
            assertTrue(boss.position !in a.questObjectives)
            assertTrue(boss.position !in a.pickups)
            assertTrue(boss.position.x != dock.x, "Field boss must not block the mandatory spawn-to-dock corridor")
            val branchStartX = minOf(a.spawn.x, boss.position.x)
            val branchEndX = maxOf(a.spawn.x, boss.position.x)
            for (x in branchStartX..branchEndX) {
                assertTrue(a.isWalkable(GridPosition(x, boss.position.y)), "Field boss side branch must remain reachable")
            }
            assertTrue(boss.maxHp > commons.maxOf { it.maxHp })
            assertTrue(boss.attackPower > commons.maxOf { it.attackPower })
            assertTrue(boss.rewardBerries > commons.maxOf { it.rewardBerries })
            assertTrue(boss.rewardMasteryExperience > commons.maxOf { it.rewardMasteryExperience })
            assertTrue(boss.respawnSteps > commons.maxOf { it.respawnSteps })
        }

        test("field boss uses the same authoritative encounter lifecycle and respawn contract") {
            var world = grandlineduo.core.model.WorldState(
                campaignId = "field-boss-fight",
                islandId = "meridian-vault",
                partyBerries = 2_000,
                players = mapOf(
                    "p1" to grandlineduo.core.model.PlayerState("p1", "A", 40, 40, 0),
                    "p2" to grandlineduo.core.model.PlayerState("p2", "B", 40, 40, 0),
                ),
            )
            val boss = ExplorationEngine.mapFor(world.campaignId, world.islandId)
                .enemies.values.single { it.rank == ExplorationEnemyRank.FIELD_BOSS }
            world = ExplorationEngine.place(world, "p1", boss.position)
            world = ExplorationCombatEngine.startIfEncountered(world, "p1")

            assertEquals(boss.id, world.activeCombat?.enemy?.id)
            assertEquals(boss.maxHp, world.activeCombat?.enemy?.maxHp)
            assertEquals(boss.attackPower, world.activeCombat?.enemy?.attackPower)
        }
    }
}
