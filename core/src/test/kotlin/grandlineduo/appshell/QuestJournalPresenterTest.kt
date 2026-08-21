package grandlineduo.appshell

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.world.ExplorationCombatEngine
import grandlineduo.game.world.ExplorationEnemyRank
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationQuestEngine
import grandlineduo.game.world.ExplorationQuestStatus
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object QuestJournalPresenterTest {
    fun register() {
        test("quest journal lists every current island quest with authoritative status objective and reward") {
            val world = world("journal-list")
            val entries = QuestJournalPresenter.entries(world, "p1")

            assertEquals(2, entries.size)
            val cache = entries.single { it.id.startsWith("local-cache-") }
            val hunt = entries.single { it.id.startsWith("boss-hunt-") }

            assertEquals(ExplorationQuestStatus.AVAILABLE, cache.status)
            assertTrue("fale com" in cache.objective.lowercase())
            assertTrue("750" in cache.reward)
            assertEquals(ExplorationQuestStatus.AVAILABLE, hunt.status)
            assertTrue("rook" in hunt.objective.lowercase())
            assertTrue("2500" in hunt.reward)
        }

        test("quest journal follows per-player cache and boss hunt progression through real victory") {
            var world = world("journal-progress")
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val cacheNpc = map.npcs.values.single { it.questId?.startsWith("local-cache-") == true }
            val cacheId = cacheNpc.questId!!
            val huntId = ExplorationQuestEngine.bossHuntQuestId(world.islandId)
            val hunter = map.npcs.values.single { it.questId == huntId }
            val boss = map.enemies.values.single { it.rank == ExplorationEnemyRank.FIELD_BOSS }

            world = ExplorationEngine.place(world, "p1", cacheNpc.position)
            world = ExplorationQuestEngine.accept(world, "p1", cacheId)
            world = ExplorationEngine.place(world, "p1", hunter.position)
            world = ExplorationQuestEngine.accept(world, "p1", huntId)

            val activeP1 = QuestJournalPresenter.entries(world, "p1")
            val untouchedP2 = QuestJournalPresenter.entries(world, "p2")
            assertTrue("encontre" in activeP1.single { it.id == cacheId }.objective.lowercase())
            assertTrue("derrote" in activeP1.single { it.id == huntId }.objective.lowercase())
            assertTrue(untouchedP2.all { it.status == ExplorationQuestStatus.AVAILABLE })

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

            val completed = QuestJournalPresenter.entries(world, "p1").single { it.id == huntId }
            assertEquals(ExplorationQuestStatus.OBJECTIVE_COMPLETE, completed.status)
            assertTrue("volte" in completed.objective.lowercase())
            assertTrue("rook" in completed.objective.lowercase())
        }

        test("exploration hub exposes a read-only quest journal menu") {
            val root = Files.createTempDirectory("gld-quest-journal-menu")
            GameSessionCoordinator(root).use { session ->
                session.startSolo("journal-menu")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Arlen"))
                val base = session.worldState()
                val world = base.copy(worldFlags = base.worldFlags + ("sg.stage" to "COMPLETE"))
                val view = GamePresenter.present(world, "p1")

                assertTrue(view.actions.any { it.id == "QUESTS" && it.kind == "MENU" })
            }
        }
    }

    private fun world(id: String) = WorldState(
        campaignId = id,
        islandId = "meridian-vault",
        partyBerries = 5_000,
        players = mapOf(
            "p1" to PlayerState("p1", "A", 100, 100, 0),
            "p2" to PlayerState("p2", "B", 100, 100, 0),
        ),
    )
}
