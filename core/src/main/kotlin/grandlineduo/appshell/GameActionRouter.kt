package grandlineduo.appshell

object GameActionRouter {
    private val WORLD_ACTION_KINDS = setOf(
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

    fun routesToWorldAction(kind: String): Boolean = kind in WORLD_ACTION_KINDS
}
