package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine
import grandlineduo.game.combat.CombatStatus
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationQuestEngineTest {
    fun register() {
        test("each island map exposes a deterministic walkable quest giver and objective") {
            val a = ExplorationEngine.mapFor("quest-seed", "stormglass-cay")
            val b = ExplorationEngine.mapFor("quest-seed", "stormglass-cay")
            assertEquals(a.npcs, b.npcs)
            assertEquals(a.questObjectives, b.questObjectives)
            val npc = a.npcs.values.single { it.questId != null }
            val objective = a.questObjectives.values.single { it.questId == npc.questId }
            assertTrue(a.isWalkable(npc.position))
            assertTrue(a.isWalkable(objective.position))
            assertTrue(npc.position != objective.position)
        }

        test("physical quest requires npc objective and return journey in order") {
            var world = baseWorld("quest-journey")
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val npc = map.npcs.values.single { it.questId != null }
            val questId = npc.questId!!
            val objective = map.questObjectives.values.single { it.questId == questId }

            var rejected = false
            try {
                ExplorationQuestEngine.accept(world, "p1", questId)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected, "Quest acceptance must require standing on the NPC tile")

            world = ExplorationEngine.place(world, "p1", npc.position)
            world = ExplorationQuestEngine.accept(world, "p1", questId)
            assertEquals(ExplorationQuestStatus.ACTIVE, ExplorationQuestEngine.status(world, "p1", questId))

            rejected = false
            try {
                ExplorationQuestEngine.progress(world, "p1", questId)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected, "Quest objective must require its physical tile")

            world = ExplorationEngine.place(world, "p1", objective.position)
            world = ExplorationQuestEngine.progress(world, "p1", questId)
            assertEquals(ExplorationQuestStatus.OBJECTIVE_COMPLETE, ExplorationQuestEngine.status(world, "p1", questId))

            rejected = false
            try {
                ExplorationQuestEngine.turnIn(world, "p1", questId)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected, "Quest turn-in must require returning to the NPC")

            val berriesBefore = world.partyBerries
            world = ExplorationEngine.place(world, "p1", npc.position)
            world = ExplorationQuestEngine.turnIn(world, "p1", questId)
            assertEquals(ExplorationQuestStatus.TURNED_IN, ExplorationQuestEngine.status(world, "p1", questId))
            assertEquals(berriesBefore + ExplorationQuestEngine.REWARD_BERRIES, world.partyBerries)
            assertEquals(1, InventoryEngine.read(world, "p1").items[ExplorationQuestEngine.REWARD_ITEM_ID])
        }

        test("quest progress is persisted independently for each human player") {
            var world = baseWorld("quest-duo")
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val npc = map.npcs.values.single { it.questId != null }
            val questId = npc.questId!!
            world = ExplorationEngine.place(world, "p1", npc.position)
            world = ExplorationEngine.place(world, "p2", npc.position)
            world = ExplorationQuestEngine.accept(world, "p1", questId)

            assertEquals(ExplorationQuestStatus.ACTIVE, ExplorationQuestEngine.status(world, "p1", questId))
            assertEquals(ExplorationQuestStatus.AVAILABLE, ExplorationQuestEngine.status(world, "p2", questId))
        }

        test("dangerous island boss hunt completes only after a real field boss victory and pays extra reward") {
            var world = WorldState(
                campaignId = "boss-hunt-quest",
                islandId = "meridian-vault",
                partyBerries = 4_000,
                players = mapOf(
                    "p1" to PlayerState("p1", "A", 50, 50, 0),
                    "p2" to PlayerState("p2", "B", 50, 50, 0),
                ),
            )
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val questId = ExplorationQuestEngine.bossHuntQuestId(world.islandId)
            val hunter = map.npcs.values.single { it.questId == questId }
            val boss = map.enemies.values.single { it.rank == ExplorationEnemyRank.FIELD_BOSS }

            assertTrue(map.isWalkable(hunter.position))
            assertTrue(hunter.position !in map.interactions)
            assertTrue(hunter.position !in map.enemies)

            world = ExplorationEngine.place(world, "p1", hunter.position)
            world = ExplorationQuestEngine.accept(world, "p1", questId)
            assertEquals(ExplorationQuestStatus.ACTIVE, ExplorationQuestEngine.status(world, "p1", questId))

            var rejected = false
            try {
                world = ExplorationEngine.place(world, "p1", boss.position)
                ExplorationQuestEngine.progress(world, "p1", questId)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected, "Boss hunt must not complete through ordinary quest interaction")

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
            assertEquals(ExplorationQuestStatus.OBJECTIVE_COMPLETE, ExplorationQuestEngine.status(world, "p1", questId))

            val berriesBeforeTurnIn = world.partyBerries
            val epicBefore = InventoryEngine.read(world, "p1").items[ExplorationQuestEngine.BOSS_REWARD_ITEM_ID] ?: 0
            world = ExplorationEngine.place(world, "p1", hunter.position)
            world = ExplorationQuestEngine.turnIn(world, "p1", questId)

            assertEquals(ExplorationQuestStatus.TURNED_IN, ExplorationQuestEngine.status(world, "p1", questId))
            assertEquals(berriesBeforeTurnIn + ExplorationQuestEngine.BOSS_REWARD_BERRIES, world.partyBerries)
            assertEquals(epicBefore + 1, InventoryEngine.read(world, "p1").items[ExplorationQuestEngine.BOSS_REWARD_ITEM_ID])
        }
    }

    private fun baseWorld(campaignId: String) = WorldState(
        campaignId = campaignId,
        islandId = "stormglass-cay",
        partyBerries = 1_000,
        players = mapOf(
            "p1" to PlayerState("p1", "A", 30, 30, 0),
            "p2" to PlayerState("p2", "B", 30, 30, 0),
        ),
    )
}
