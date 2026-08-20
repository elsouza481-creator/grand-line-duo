package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
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
            assertTrue(enemy.maxHp > 0)
            assertTrue(enemy.attackPower > 0)
            assertTrue(enemy.rewardBerries > 0)
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
