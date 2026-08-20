package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine
import grandlineduo.game.combat.CombatStatus
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationCombatEngineTest {
    fun register() {
        test("each island exposes one deterministic walkable hostile encounter without physical overlap") {
            val a = ExplorationEngine.mapFor("free-roam-map", "stormglass-cay")
            val b = ExplorationEngine.mapFor("free-roam-map", "stormglass-cay")

            assertEquals(a.enemies, b.enemies)
            val enemy = a.enemies.values.single()
            assertTrue(a.isWalkable(enemy.position))
            assertTrue(enemy.position != a.spawn)
            assertTrue(enemy.position !in a.interactions)
            assertTrue(enemy.position !in a.npcs)
            assertTrue(enemy.position !in a.questObjectives)
            assertTrue(enemy.position !in a.pickups)
            assertTrue(enemy.archetype in ExplorationEnemyArchetype.entries)
            assertTrue(enemy.maxHp > 0)
            assertTrue(enemy.attackPower > 0)
            assertTrue(enemy.rewardBerries > 0)
            assertTrue(enemy.rewardItemId.isNotBlank())
            assertTrue(enemy.rewardItemAmount > 0)
        }

        test("enemy archetypes have distinct combat roles and all scale with island danger") {
            val lowDanger = ExplorationEnemyArchetype.entries.associateWith {
                ExplorationEnemyCatalog.profile(it, danger = 2)
            }
            val highDanger = ExplorationEnemyArchetype.entries.associateWith {
                ExplorationEnemyCatalog.profile(it, danger = 8)
            }

            assertEquals(
                ExplorationEnemyArchetype.entries.size,
                lowDanger.values.map { Triple(it.maxHp, it.attackPower, it.rewardBerries) }.toSet().size,
            )
            ExplorationEnemyArchetype.entries.forEach { archetype ->
                val low = lowDanger.getValue(archetype)
                val high = highDanger.getValue(archetype)
                assertTrue(high.maxHp > low.maxHp)
                assertTrue(high.attackPower > low.attackPower)
                assertTrue(high.rewardBerries > low.rewardBerries)
                assertTrue(low.rewardItemId.isNotBlank())
                assertTrue(high.rewardItemId.isNotBlank())
            }

            val bruiser = ExplorationEnemyCatalog.profile(ExplorationEnemyArchetype.BRUISER, danger = 5)
            val marksman = ExplorationEnemyCatalog.profile(ExplorationEnemyArchetype.MARKSMAN, danger = 5)
            assertTrue(bruiser.maxHp > marksman.maxHp)
            assertTrue(marksman.attackPower > bruiser.attackPower)
        }

        test("stepping onto a live hostile tile starts free roam combat but ordinary movement does not") {
            var world = world("free-roam-start")
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val enemy = map.enemies.values.single()

            val unchanged = ExplorationCombatEngine.startIfEncountered(world, "p1")
            assertEquals(null, unchanged.activeCombat)

            world = ExplorationEngine.place(world, "p1", enemy.position)
            val started = ExplorationCombatEngine.startIfEncountered(world, "p1")
            val combat = started.activeCombat!!

            assertEquals(enemy.id, combat.enemy.id)
            assertEquals(enemy.maxHp, combat.enemy.maxHp)
            assertEquals(enemy.attackPower, combat.enemy.attackPower)
            assertEquals(world.players.getValue("p1").hp, combat.players.getValue("p1").hp)
            assertEquals(world.players.getValue("p2").hp, combat.players.getValue("p2").hp)
            assertTrue(ExplorationCombatEngine.isActive(started))
        }

        test("free roam victory grants deterministic loot to each surviving fighter exactly once") {
            var world = world("free-roam-loot")
            val enemy = ExplorationEngine.mapFor(world.campaignId, world.islandId).enemies.values.single()
            world = ExplorationEngine.place(world, "p1", enemy.position)
            world = ExplorationCombatEngine.startIfEncountered(world, "p1")
            val combat = world.activeCombat!!
            val won = world.copy(
                activeCombat = combat.copy(
                    enemy = combat.enemy.copy(hp = 0),
                    status = CombatStatus.VICTORY,
                )
            )

            val beforeP1 = InventoryEngine.read(won, "p1").items[enemy.rewardItemId] ?: 0
            val beforeP2 = InventoryEngine.read(won, "p2").items[enemy.rewardItemId] ?: 0
            val completed = ExplorationCombatEngine.completeVictory(won)
            val afterP1 = InventoryEngine.read(completed, "p1").items[enemy.rewardItemId] ?: 0
            val afterP2 = InventoryEngine.read(completed, "p2").items[enemy.rewardItemId] ?: 0

            assertEquals(beforeP1 + enemy.rewardItemAmount, afterP1)
            assertEquals(beforeP2 + enemy.rewardItemAmount, afterP2)
            val revisited = ExplorationCombatEngine.startIfEncountered(completed, "p1")
            assertEquals(afterP1, InventoryEngine.read(revisited, "p1").items[enemy.rewardItemId])
            assertEquals(afterP2, InventoryEngine.read(revisited, "p2").items[enemy.rewardItemId])
        }

        test("free roam victory removes the encounter rewards party once and prevents respawn") {
            var world = world("free-roam-victory")
            val enemy = ExplorationEngine.mapFor(world.campaignId, world.islandId).enemies.values.single()
            world = ExplorationEngine.place(world, "p1", enemy.position)
            world = ExplorationCombatEngine.startIfEncountered(world, "p1")
            val berriesBefore = world.partyBerries
            val combat = world.activeCombat!!
            val won = world.copy(
                activeCombat = combat.copy(
                    enemy = combat.enemy.copy(hp = 0),
                    status = CombatStatus.VICTORY,
                )
            )

            val completed = ExplorationCombatEngine.completeVictory(won)

            assertEquals(null, completed.activeCombat)
            assertTrue(ExplorationCombatEngine.isDefeated(completed, enemy.id))
            assertEquals(berriesBefore + enemy.rewardBerries, completed.partyBerries)
            val revisited = ExplorationCombatEngine.startIfEncountered(completed, "p1")
            assertEquals(null, revisited.activeCombat)
            assertEquals(completed.partyBerries, revisited.partyBerries)
        }
    }

    private fun world(id: String) = WorldState(
        campaignId = id,
        islandId = "stormglass-cay",
        partyBerries = 1_000,
        players = mapOf(
            "p1" to PlayerState("p1", "A", 36, 40, 0),
            "p2" to PlayerState("p2", "B", 31, 35, 0),
        ),
    )
}
