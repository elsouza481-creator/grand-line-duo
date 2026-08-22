package grandlineduo.game.quest

import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object QuestObjectiveRouterTest {
    fun register() {
        test("bound enemy defeated advances only the source hunt") {
            val first = hunt("hunt-a")
            val second = hunt("hunt-b")
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

        test("objective router ignores wrong source target island ready and non hunt") {
            val active = hunt("hunt-a")
            val ready = hunt("hunt-ready", status = QuestStatus.READY_TO_TURN_IN, progress = 3)
            val collect = hunt("collect-a", type = QuestType.COLLECT)
            val world = worldWith(active, ready, collect)

            val wrongSource = QuestObjectiveRouter.apply(
                world,
                QuestObjectiveEvent(QuestObjectiveEventType.ENEMY_DEFEATED, "dock-raiders", "shells-town", 1, "missing"),
            )
            assertEquals(world, wrongSource)

            val wrongTarget = QuestObjectiveRouter.apply(
                world,
                QuestObjectiveEvent(QuestObjectiveEventType.ENEMY_DEFEATED, "other-raiders", "shells-town", 1, "hunt-a"),
            )
            assertEquals(world, wrongTarget)

            val wrongIsland = QuestObjectiveRouter.apply(
                world,
                QuestObjectiveEvent(QuestObjectiveEventType.ENEMY_DEFEATED, "dock-raiders", "other-island", 1, "hunt-a"),
            )
            assertEquals(world, wrongIsland)

            val readyEvent = QuestObjectiveRouter.apply(
                world,
                QuestObjectiveEvent(QuestObjectiveEventType.ENEMY_DEFEATED, "dock-raiders", "shells-town", 1, "hunt-ready"),
            )
            assertEquals(world, readyEvent)

            val nonHuntEvent = QuestObjectiveRouter.apply(
                world,
                QuestObjectiveEvent(QuestObjectiveEventType.ENEMY_DEFEATED, "dock-raiders", "shells-town", 1, "collect-a"),
            )
            assertEquals(world, nonHuntEvent)
        }

        test("future objective event types are no op in this slice") {
            val world = worldWith(hunt("hunt-a"))
            QuestObjectiveEventType.entries
                .filter { it != QuestObjectiveEventType.ENEMY_DEFEATED }
                .forEach { type ->
                    val next = QuestObjectiveRouter.apply(
                        world,
                        QuestObjectiveEvent(type, "dock-raiders", "shells-town", 1, "hunt-a"),
                    )
                    assertEquals(world, next)
                }
        }

        test("unbound exact matches advance deterministically regardless of map insertion order") {
            val a = hunt("a-hunt")
            val b = hunt("b-hunt")
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

        test("objective progress clamps at hunt requirement") {
            val nearReady = hunt("hunt-a", progress = 2)
            val world = worldWith(nearReady)

            val next = QuestObjectiveRouter.apply(
                world,
                QuestObjectiveEvent(
                    QuestObjectiveEventType.ENEMY_DEFEATED,
                    "dock-raiders",
                    "shells-town",
                    99,
                    "hunt-a",
                ),
            )

            assertEquals(3, next.questBoard.active.getValue("hunt-a").progress)
            assertEquals(QuestStatus.READY_TO_TURN_IN, next.questBoard.active.getValue("hunt-a").status)
        }
    }

    private fun hunt(
        id: String,
        status: QuestStatus = QuestStatus.ACTIVE,
        progress: Int = 0,
        type: QuestType = QuestType.HUNT,
    ): QuestProgress {
        val definition = QuestDefinition(
            questId = id,
            islandId = "shells-town",
            title = "Caçada $id",
            type = type,
            rarity = QuestRarity.COMMON,
            issuerFaction = "CIVILIANS",
            targetId = "dock-raiders",
            requiredAmount = 3,
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
