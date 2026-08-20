package grandlineduo.appshell

import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.world.ExplorationCombatEngine
import grandlineduo.game.world.ExplorationEnemyRank
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object ExplorationFieldBossPresentationTest {
    fun register() {
        test("presenter exposes active field boss difficulty risk reward respawn and first-clear intel") {
            val root = Files.createTempDirectory("gld-field-boss-present")
            GameSessionCoordinator(root).use { session ->
                session.startSolo("field-boss-present")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Arlen"))
                val base = session.worldState()
                val world = base.copy(
                    islandId = "meridian-vault",
                    worldFlags = base.worldFlags + ("sg.stage" to "COMPLETE"),
                )
                val boss = ExplorationEngine.mapFor(world.campaignId, world.islandId)
                    .enemies.values.single { it.rank == ExplorationEnemyRank.FIELD_BOSS }

                val view = GamePresenter.present(world, "p1")
                val body = view.body.lowercase()

                assertTrue(boss.position in requireNotNull(view.exploration).visibleEnemies)
                assertTrue("chefe de campo" in body)
                assertTrue("dificuldade ${boss.difficulty.name.lowercase()}" in body)
                assertTrue(boss.name.lowercase() in body)
                assertTrue("${boss.maxHp} pv" in body)
                assertTrue("ataque ${boss.attackPower}" in body)
                assertTrue("${boss.rewardBerries} berries" in body)
                assertTrue("${boss.rewardMasteryExperience} xp" in body)
                assertTrue("${boss.respawnSteps} passos" in body)
                assertTrue("primeira vitória 2x" in body)
                assertTrue("berries + xp" in body)
            }
        }

        test("presenter keeps field boss cooldown visible after victory without hostile marker or first-clear offer") {
            val root = Files.createTempDirectory("gld-field-boss-cooldown-present")
            GameSessionCoordinator(root).use { session ->
                session.startSolo("field-boss-cooldown-present")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Arlen"))
                val base = session.worldState()
                var world = base.copy(
                    islandId = "meridian-vault",
                    worldFlags = base.worldFlags + ("sg.stage" to "COMPLETE"),
                )
                val boss = ExplorationEngine.mapFor(world.campaignId, world.islandId)
                    .enemies.values.single { it.rank == ExplorationEnemyRank.FIELD_BOSS }

                world = ExplorationEngine.place(world, "p1", boss.position)
                world = ExplorationCombatEngine.startIfEncountered(world, "p1")
                val combat = requireNotNull(world.activeCombat)
                world = ExplorationCombatEngine.completeVictory(
                    world.copy(
                        activeCombat = combat.copy(
                            enemy = combat.enemy.copy(hp = 0),
                            status = CombatStatus.VICTORY,
                        )
                    )
                )
                val remaining = ExplorationCombatEngine.stepsUntilRespawn(world, boss.id)
                val view = GamePresenter.present(world, "p1")
                val body = view.body.lowercase()

                assertTrue(boss.position !in requireNotNull(view.exploration).visibleEnemies)
                assertTrue("chefe de campo" in body)
                assertTrue("dificuldade ${boss.difficulty.name.lowercase()}" in body)
                assertTrue("reaparece em $remaining passos" in body)
                assertTrue("primeira vitória 2x" !in body)
            }
        }
    }
}
