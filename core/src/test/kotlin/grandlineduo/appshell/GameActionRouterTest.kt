package grandlineduo.appshell

import grandlineduo.test.assertTrue
import grandlineduo.test.test

object GameActionRouterTest {
    fun register() {
        test("Android world action routing includes field sparring and every physical world command family") {
            val expectedWorldKinds = setOf(
                "EXPLORE_MOVE",
                "QUEST_ACCEPT",
                "QUEST_PROGRESS",
                "QUEST_TURN_IN",
                "LOOT_COLLECT",
                "DUEL_CHALLENGE",
                "DUEL_FIELD_CHALLENGE",
                "DUEL_ACCEPT",
                "DUEL_DECLINE",
                "DUEL_CANCEL",
                "DUEL_ACTION",
                "DUEL_FORFEIT",
            )
            expectedWorldKinds.forEach { kind ->
                assertTrue(GameActionRouter.routesToWorldAction(kind), "$kind must route to submitWorldAction")
            }
            listOf("SCENARIO", "ARC", "COMBAT", "POWER", "VOYAGE", "CAMPAIGN", "MENU").forEach { kind ->
                assertTrue(!GameActionRouter.routesToWorldAction(kind), "$kind must keep its dedicated route")
            }
        }
    }
}
