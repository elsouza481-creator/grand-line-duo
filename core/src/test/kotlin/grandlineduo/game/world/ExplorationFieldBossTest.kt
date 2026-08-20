package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine
import grandlineduo.game.ItemCatalog
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.character.ClassMasteryEngine
import grandlineduo.game.character.ClassPath
import grandlineduo.game.combat.CombatStatus
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
            var world = WorldState(
                campaignId = "field-boss-fight",
                islandId = "meridian-vault",
                partyBerries = 2_000,
                players = mapOf(
                    "p1" to PlayerState("p1", "A", 40, 40, 0),
                    "p2" to PlayerState("p2", "B", 40, 40, 0),
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

        test("first field boss clear doubles berries primary mastery xp and grants one legendary only once") {
            val created = CharacterCreation.create(
                CharacterCreationTest.validDraft().copy(name = "A", classPath = ClassPath.SWORDSMAN)
            ) as CharacterCreationResult.Success
            val profile = created.profile
            var world = WorldState(
                campaignId = "field-boss-first-clear",
                islandId = "meridian-vault",
                partyBerries = 5_000,
                players = mapOf(
                    "p1" to PlayerState(
                        "p1", profile.name, profile.maxHp, profile.maxHp, 0,
                        profile.maxEnergy, profile.maxEnergy, profile,
                    ),
                    "p2" to PlayerState("p2", "B", 40, 40, 0),
                ),
            )
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val boss = map.enemies.values.single { it.rank == ExplorationEnemyRank.FIELD_BOSS }
            val berriesBefore = world.partyBerries
            val masteryBefore = world.players.getValue("p1").profile!!.classMastery!!

            world = ExplorationEngine.place(world, "p1", boss.position)
            world = ExplorationCombatEngine.startIfEncountered(world, "p1")
            world = winActiveEncounter(world)

            val expectedFirstMastery = ClassMasteryEngine.train(
                masteryBefore,
                ClassPath.SWORDSMAN,
                boss.rewardMasteryExperience * 2,
            )
            assertEquals(berriesBefore + boss.rewardBerries * 2L, world.partyBerries)
            assertEquals(expectedFirstMastery, world.players.getValue("p1").profile!!.classMastery)
            assertEquals(1, InventoryEngine.read(world, "p1").items[ItemCatalog.FIELD_BOSS_LEGENDARY_ID] ?: 0)
            assertEquals(0, InventoryEngine.read(world, "p2").items[ItemCatalog.FIELD_BOSS_LEGENDARY_ID] ?: 0)

            val berriesAfterFirst = world.partyBerries
            val masteryAfterFirst = world.players.getValue("p1").profile!!.classMastery!!
            world = ExplorationEngine.place(world, "p1", map.spawn)
            repeat(boss.respawnSteps) { index ->
                world = ExplorationEngine.move(
                    world,
                    "p1",
                    if (index % 2 == 0) ExplorationDirection.EAST else ExplorationDirection.WEST,
                )
            }
            assertTrue(!ExplorationCombatEngine.isDefeated(world, boss.id))

            world = ExplorationEngine.place(world, "p1", boss.position)
            world = ExplorationCombatEngine.startIfEncountered(world, "p1")
            world = winActiveEncounter(world)

            val expectedRepeatMastery = ClassMasteryEngine.train(
                masteryAfterFirst,
                ClassPath.SWORDSMAN,
                boss.rewardMasteryExperience,
            )
            assertEquals(berriesAfterFirst + boss.rewardBerries, world.partyBerries)
            assertEquals(expectedRepeatMastery, world.players.getValue("p1").profile!!.classMastery)
            assertEquals(1, InventoryEngine.read(world, "p1").items[ItemCatalog.FIELD_BOSS_LEGENDARY_ID] ?: 0)
            assertEquals(0, InventoryEngine.read(world, "p2").items[ItemCatalog.FIELD_BOSS_LEGENDARY_ID] ?: 0)
        }
    }

    private fun winActiveEncounter(world: WorldState): WorldState {
        val combat = requireNotNull(world.activeCombat)
        return ExplorationCombatEngine.completeVictory(
            world.copy(
                activeCombat = combat.copy(
                    enemy = combat.enemy.copy(hp = 0),
                    status = CombatStatus.VICTORY,
                )
            )
        )
    }
}
