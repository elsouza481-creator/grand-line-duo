package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.character.ClassMasteryEngine
import grandlineduo.game.character.ClassPath
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationCombatEngineTest {
    fun register() {
        test("each island exposes three deterministic walkable hostile encounters without physical overlap") {
            val a = ExplorationEngine.mapFor("free-roam-map", "stormglass-cay")
            val b = ExplorationEngine.mapFor("free-roam-map", "stormglass-cay")

            assertEquals(a.enemies, b.enemies)
            assertEquals(3, a.enemies.size)
            assertEquals(3, a.enemies.keys.toSet().size)
            a.enemies.values.forEach { enemy ->
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
                assertTrue(enemy.rewardMasteryExperience > 0)
                assertTrue(enemy.respawnSteps > 0)
            }
        }

        test("enemy archetypes have distinct combat roles scale rewards with danger and declare tactical openings") {
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
                assertTrue(high.rewardMasteryExperience > low.rewardMasteryExperience)
                assertTrue(low.rewardItemId.isNotBlank())
                assertTrue(high.rewardItemId.isNotBlank())
                assertTrue(low.respawnSteps > 0)
                assertTrue(high.respawnSteps >= low.respawnSteps)
            }

            val bruiser = ExplorationEnemyCatalog.profile(ExplorationEnemyArchetype.BRUISER, danger = 5)
            val skirmisher = ExplorationEnemyCatalog.profile(ExplorationEnemyArchetype.SKIRMISHER, danger = 5)
            val marksman = ExplorationEnemyCatalog.profile(ExplorationEnemyArchetype.MARKSMAN, danger = 5)
            val officer = ExplorationEnemyCatalog.profile(ExplorationEnemyArchetype.OFFICER, danger = 5)
            assertTrue(bruiser.maxHp > marksman.maxHp)
            assertTrue(marksman.attackPower > bruiser.attackPower)
            assertEquals(EnemyAttackType.HEAVY_STRIKE, bruiser.initialAttackType)
            assertEquals(EnemyAttackType.SWEEP, skirmisher.initialAttackType)
            assertEquals(EnemyAttackType.HEAVY_STRIKE, marksman.initialAttackType)
            assertEquals(EnemyAttackType.SWEEP, officer.initialAttackType)
        }

        test("stepping onto a live hostile tile starts free roam combat but ordinary movement does not") {
            var world = world("free-roam-start")
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val enemy = map.enemies.values.sortedBy { it.id }.first()

            val unchanged = ExplorationCombatEngine.startIfEncountered(world, "p1")
            assertEquals(null, unchanged.activeCombat)

            world = ExplorationEngine.place(world, "p1", enemy.position)
            val started = ExplorationCombatEngine.startIfEncountered(world, "p1")
            val combat = started.activeCombat!!

            assertEquals(enemy.id, combat.enemy.id)
            assertEquals(enemy.maxHp, combat.enemy.maxHp)
            assertEquals(enemy.attackPower, combat.enemy.attackPower)
            assertEquals(enemy.initialAttackType, combat.telegraph.type)
            assertEquals(world.players.getValue("p1").hp, combat.players.getValue("p1").hp)
            assertEquals(world.players.getValue("p2").hp, combat.players.getValue("p2").hp)
            assertTrue(ExplorationCombatEngine.isActive(started))
        }

        test("free roam victory grants deterministic loot to each surviving fighter exactly once") {
            var world = world("free-roam-loot")
            val enemy = ExplorationEngine.mapFor(world.campaignId, world.islandId).enemies.values.sortedBy { it.id }.first()
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

        test("free roam victory trains only the surviving fighters primary class") {
            var world = masteryWorld("free-roam-mastery")
            val enemy = ExplorationEngine.mapFor(world.campaignId, world.islandId).enemies.values.sortedBy { it.id }.first()
            val p1Before = world.players.getValue("p1").profile!!.classMastery!!
            val p2Before = world.players.getValue("p2").profile!!.classMastery!!
            assertEquals(9L, p1Before.experienceOf(ClassPath.GUNNER))

            world = ExplorationEngine.place(world, "p1", enemy.position)
            world = ExplorationCombatEngine.startIfEncountered(world, "p1")
            val combat = world.activeCombat!!
            val p2Down = combat.players.getValue("p2").copy(hp = 0)
            val won = world.copy(
                activeCombat = combat.copy(
                    players = combat.players + ("p2" to p2Down),
                    enemy = combat.enemy.copy(hp = 0),
                    status = CombatStatus.VICTORY,
                )
            )

            val completed = ExplorationCombatEngine.completeVictory(won)
            val p1After = completed.players.getValue("p1").profile!!.classMastery!!
            val p2After = completed.players.getValue("p2").profile!!.classMastery!!

            assertEquals(enemy.rewardMasteryExperience.toLong(), p1After.experienceOf(ClassPath.SWORDSMAN))
            assertEquals(9L, p1After.experienceOf(ClassPath.GUNNER))
            assertEquals(p2Before, p2After)
        }

        test("free roam mastery reward ignores legacy profiles without a chosen class") {
            var world = world("free-roam-mastery-legacy")
            val legacyProfile = (CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success).profile
            world = world.copy(
                players = world.players + ("p1" to world.players.getValue("p1").copy(profile = legacyProfile)),
            )
            val enemy = ExplorationEngine.mapFor(world.campaignId, world.islandId).enemies.values.sortedBy { it.id }.first()
            world = ExplorationEngine.place(world, "p1", enemy.position)
            world = ExplorationCombatEngine.startIfEncountered(world, "p1")
            val combat = world.activeCombat!!
            val completed = ExplorationCombatEngine.completeVictory(
                world.copy(
                    activeCombat = combat.copy(
                        enemy = combat.enemy.copy(hp = 0),
                        status = CombatStatus.VICTORY,
                    )
                )
            )

            assertEquals(null, completed.players.getValue("p1").profile!!.classMastery)
        }

        test("free roam victory removes only that encounter rewards party once during cooldown") {
            var world = world("free-roam-victory")
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val enemy = map.enemies.values.sortedBy { it.id }.first()
            val otherIds = map.enemies.values.map { it.id }.toSet() - enemy.id
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
            otherIds.forEach { assertTrue(!ExplorationCombatEngine.isDefeated(completed, it)) }
            assertEquals(enemy.respawnSteps, ExplorationCombatEngine.stepsUntilRespawn(completed, enemy.id))
            assertEquals(berriesBefore + enemy.rewardBerries, completed.partyBerries)
            val revisited = ExplorationCombatEngine.startIfEncountered(completed, "p1")
            assertEquals(null, revisited.activeCombat)
            assertEquals(completed.partyBerries, revisited.partyBerries)
        }

        test("new victories respawn after enough successful exploration steps and can reward a later kill") {
            var world = world("free-roam-respawn")
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val enemy = map.enemies.values.sortedBy { it.id }.first()
            world = ExplorationEngine.place(world, "p1", enemy.position)
            world = ExplorationCombatEngine.startIfEncountered(world, "p1")
            val firstCombat = world.activeCombat!!
            world = ExplorationCombatEngine.completeVictory(
                world.copy(
                    activeCombat = firstCombat.copy(
                        enemy = firstCombat.enemy.copy(hp = 0),
                        status = CombatStatus.VICTORY,
                    )
                )
            )
            val berriesAfterFirstKill = world.partyBerries

            assertEquals(0L, ExplorationEngine.explorationSteps(world))
            assertTrue(ExplorationCombatEngine.isDefeated(world, enemy.id))
            assertEquals(enemy.respawnSteps, ExplorationCombatEngine.stepsUntilRespawn(world, enemy.id))

            world = ExplorationEngine.place(world, "p1", map.spawn)
            world = walkSuccessfulSteps(world, enemy.respawnSteps - 1)
            assertTrue(ExplorationCombatEngine.isDefeated(world, enemy.id))
            assertEquals(1, ExplorationCombatEngine.stepsUntilRespawn(world, enemy.id))

            world = walkSuccessfulSteps(world, 1)
            assertTrue(!ExplorationCombatEngine.isDefeated(world, enemy.id))
            assertEquals(0, ExplorationCombatEngine.stepsUntilRespawn(world, enemy.id))
            assertEquals(enemy.respawnSteps.toLong(), ExplorationEngine.explorationSteps(world))

            world = ExplorationEngine.place(world, "p1", enemy.position)
            world = ExplorationCombatEngine.startIfEncountered(world, "p1")
            val secondCombat = world.activeCombat!!
            assertEquals(enemy.id, secondCombat.enemy.id)
            world = ExplorationCombatEngine.completeVictory(
                world.copy(
                    activeCombat = secondCombat.copy(
                        enemy = secondCombat.enemy.copy(hp = 0),
                        status = CombatStatus.VICTORY,
                    )
                )
            )

            assertEquals(berriesAfterFirstKill + enemy.rewardBerries, world.partyBerries)
            assertTrue(ExplorationCombatEngine.isDefeated(world, enemy.id))
            assertEquals(enemy.respawnSteps, ExplorationCombatEngine.stepsUntilRespawn(world, enemy.id))
        }

        test("legacy defeated encounter without respawn metadata stays permanently defeated") {
            val base = world("free-roam-legacy-defeat")
            val enemy = ExplorationEngine.mapFor(base.campaignId, base.islandId).enemies.values.sortedBy { it.id }.first()
            var world = base.copy(
                worldFlags = base.worldFlags + ("explore.${base.islandId}.enemy.${enemy.id}.defeated" to "true"),
            )
            world = ExplorationEngine.place(world, "p1", ExplorationEngine.mapFor(world.campaignId, world.islandId).spawn)
            world = walkSuccessfulSteps(world, enemy.respawnSteps * 3)
            world = ExplorationEngine.place(world, "p1", enemy.position)

            assertTrue(ExplorationCombatEngine.isDefeated(world, enemy.id))
            assertEquals(Int.MAX_VALUE, ExplorationCombatEngine.stepsUntilRespawn(world, enemy.id))
            assertEquals(null, ExplorationCombatEngine.startIfEncountered(world, "p1").activeCombat)
        }
    }

    private fun walkSuccessfulSteps(initial: WorldState, steps: Int): WorldState {
        var world = initial
        repeat(steps) { index ->
            val direction = if (index % 2 == 0) ExplorationDirection.EAST else ExplorationDirection.WEST
            world = ExplorationEngine.move(world, "p1", direction)
        }
        return world
    }

    private fun masteryWorld(id: String): WorldState {
        val p1Created = CharacterCreation.create(
            CharacterCreationTest.validDraft().copy(name = "A", classPath = ClassPath.SWORDSMAN)
        ) as CharacterCreationResult.Success
        val p1Mastery = ClassMasteryEngine.train(
            p1Created.profile.classMastery!!,
            ClassPath.GUNNER,
            9,
        )
        val p1Profile = p1Created.profile.copy(classMastery = p1Mastery)
        val p2Created = CharacterCreation.create(
            CharacterCreationTest.validDraft().copy(name = "B", classPath = ClassPath.NAVIGATOR)
        ) as CharacterCreationResult.Success
        val p2Profile = p2Created.profile
        return WorldState(
            campaignId = id,
            islandId = "stormglass-cay",
            partyBerries = 1_000,
            players = mapOf(
                "p1" to PlayerState("p1", p1Profile.name, p1Profile.maxHp, p1Profile.maxHp, 0, p1Profile.maxEnergy, p1Profile.maxEnergy, p1Profile),
                "p2" to PlayerState("p2", p2Profile.name, p2Profile.maxHp, p2Profile.maxHp, 0, p2Profile.maxEnergy, p2Profile.maxEnergy, p2Profile),
            ),
        )
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
