package grandlineduo.game.quest

import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object QuestObjectiveRouterTest {
    fun register() {
        test("bound enemy defeated advances only the source hunt") {
            val first = objective("hunt-a", QuestType.HUNT, "dock-raiders", 3)
            val second = objective("hunt-b", QuestType.HUNT, "dock-raiders", 3)
            val world = worldWith(first, second)

            val next = QuestObjectiveRouter.apply(
                world,
                QuestObjectiveEvent(
                    type = QuestObjectiveEventType.ENEMY_DEFEATED,
                    targetId = "dock-raiders",
                    islandId = "shells-town",
                    amount = 2,
                    sourceQuestId = "hunt-a",
                ),
            )

            assertEquals(2, next.questBoard.active.getValue("hunt-a").progress)
            assertEquals(0, next.questBoard.active.getValue("hunt-b").progress)
        }

        test("location visited advances only the exact bound explore contract") {
            val exploreA = objective("explore-a", QuestType.EXPLORE, "forgotten-ruins", 3)
            val exploreB = objective("explore-b", QuestType.EXPLORE, "forgotten-ruins", 3)
            val world = worldWith(exploreA, exploreB)

            val next = QuestObjectiveRouter.apply(
                world,
                QuestObjectiveEvent(
                    QuestObjectiveEventType.LOCATION_VISITED,
                    "forgotten-ruins",
                    "shells-town",
                    1,
                    "explore-a",
                ),
            )

            assertEquals(1, next.questBoard.active.getValue("explore-a").progress)
            assertEquals(0, next.questBoard.active.getValue("explore-b").progress)
        }

        test("item acquired advances only the exact bound collect contract") {
            val collectA = objective("collect-a", QuestType.COLLECT, "medical-supplies", 4)
            val collectB = objective("collect-b", QuestType.COLLECT, "medical-supplies", 4)
            val world = worldWith(collectA, collectB)

            val next = QuestObjectiveRouter.apply(
                world,
                QuestObjectiveEvent(
                    QuestObjectiveEventType.ITEM_ACQUIRED,
                    "medical-supplies",
                    "shells-town",
                    1,
                    "collect-a",
                ),
            )

            assertEquals(1, next.questBoard.active.getValue("collect-a").progress)
            assertEquals(0, next.questBoard.active.getValue("collect-b").progress)
        }

        test("objective router ignores wrong source target island ready and mismatched type") {
            val hunt = objective("hunt-a", QuestType.HUNT, "dock-raiders", 3)
            val readyHunt = objective("hunt-ready", QuestType.HUNT, "dock-raiders", 3, QuestStatus.READY_TO_TURN_IN, 3)
            val collect = objective("collect-a", QuestType.COLLECT, "medical-supplies", 4)
            val explore = objective("explore-a", QuestType.EXPLORE, "forgotten-ruins", 3)
            val world = worldWith(hunt, readyHunt, collect, explore)

            assertEquals(
                world,
                QuestObjectiveRouter.apply(
                    world,
                    QuestObjectiveEvent(QuestObjectiveEventType.ENEMY_DEFEATED, "dock-raiders", "shells-town", 1, "missing"),
                ),
            )
            assertEquals(
                world,
                QuestObjectiveRouter.apply(
                    world,
                    QuestObjectiveEvent(QuestObjectiveEventType.ENEMY_DEFEATED, "other-raiders", "shells-town", 1, "hunt-a"),
                ),
            )
            assertEquals(
                world,
                QuestObjectiveRouter.apply(
                    world,
                    QuestObjectiveEvent(QuestObjectiveEventType.ENEMY_DEFEATED, "dock-raiders", "other-island", 1, "hunt-a"),
                ),
            )
            assertEquals(
                world,
                QuestObjectiveRouter.apply(
                    world,
                    QuestObjectiveEvent(QuestObjectiveEventType.ENEMY_DEFEATED, "dock-raiders", "shells-town", 1, "hunt-ready"),
                ),
            )
            assertEquals(
                world,
                QuestObjectiveRouter.apply(
                    world,
                    QuestObjectiveEvent(QuestObjectiveEventType.ENEMY_DEFEATED, "medical-supplies", "shells-town", 1, "collect-a"),
                ),
            )
            assertEquals(
                world,
                QuestObjectiveRouter.apply(
                    world,
                    QuestObjectiveEvent(QuestObjectiveEventType.ITEM_ACQUIRED, "forgotten-ruins", "shells-town", 1, "explore-a"),
                ),
            )
        }

        test("reserved and future objective event types remain no op") {
            val world = worldWith(objective("explore-a", QuestType.EXPLORE, "forgotten-ruins", 3))
            listOf(
                QuestObjectiveEventType.ISLAND_VISITED,
                QuestObjectiveEventType.NPC_RESCUED,
                QuestObjectiveEventType.ESCORT_ARRIVED,
                QuestObjectiveEventType.CLUE_DISCOVERED,
            ).forEach { type ->
                assertEquals(
                    world,
                    QuestObjectiveRouter.apply(
                        world,
                        QuestObjectiveEvent(type, "forgotten-ruins", "shells-town", 1, "explore-a"),
                    ),
                )
            }
        }

        test("unbound exact matches advance deterministically regardless of map insertion order") {
            val a = objective("a-hunt", QuestType.HUNT, "dock-raiders", 3)
            val b = objective("b-hunt", QuestType.HUNT, "dock-raiders", 3)
            val forward = worldWith(a, b)
            val reverse = worldWith(b, a)
            val event = QuestObjectiveEvent(
                QuestObjectiveEventType.ENEMY_DEFEATED,
                "dock-raiders",
                "shells-town",
                1,
                sourceQuestId = null,
            )

            val forwardNext = QuestObjectiveRouter.apply(forward, event)
            val reverseNext = QuestObjectiveRouter.apply(reverse, event)

            assertEquals(forwardNext.questBoard.active, reverseNext.questBoard.active)
            assertEquals(1, forwardNext.questBoard.active.getValue("a-hunt").progress)
            assertEquals(1, forwardNext.questBoard.active.getValue("b-hunt").progress)
        }

        test("objective progress clamps at requirement") {
            val nearReady = objective("explore-a", QuestType.EXPLORE, "forgotten-ruins", 3, progress = 2)
            val world = worldWith(nearReady)

            val next = QuestObjectiveRouter.apply(
                world,
                QuestObjectiveEvent(
                    QuestObjectiveEventType.LOCATION_VISITED,
                    "forgotten-ruins",
                    "shells-town",
                    99,
                    "explore-a",
                ),
            )

            assertEquals(3, next.questBoard.active.getValue("explore-a").progress)
            assertEquals(QuestStatus.READY_TO_TURN_IN, next.questBoard.active.getValue("explore-a").status)
        }
    }

    private fun objective(
        id: String,
        type: QuestType,
        targetId: String,
        required: Int,
        status: QuestStatus = QuestStatus.ACTIVE,
        progress: Int = 0,
    ): QuestProgress {
        val definition = QuestDefinition(
            questId = id,
            islandId = "shells-town",
            title = "Contrato $id",
            type = type,
            rarity = QuestRarity.COMMON,
            issuerFaction = "CIVILIANS",
            targetId = targetId,
            requiredAmount = required,
        )
        return QuestProgress(
            definition = definition,
            status = status,
            progress = progress,
            acceptedBy = "p1",
        )
    }

    private fun worldWith(vararg quests: QuestProgress): WorldState = WorldState(
        campaignId = "objective-router-test",
        islandId = "shells-town",
        questBoard = QuestBoardState(active = quests.associateBy { it.definition.questId }),
    )
}
