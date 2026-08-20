package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.InventoryEngine
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object ExplorationQuestCommandIntegrationTest {
    fun register() {
        test("authoritative world commands complete a physical quest and reward exactly once") {
            var initial = WorldState(
                campaignId = "quest-command",
                islandId = "stormglass-cay",
                partyBerries = 2_000,
                players = mapOf(
                    "p1" to PlayerState("p1", "A", 30, 30, 0),
                    "p2" to PlayerState("p2", "B", 30, 30, 0),
                ),
            )
            val map = ExplorationEngine.mapFor(initial.campaignId, initial.islandId)
            val npc = map.npcs.values.single { it.questId != null }
            val questId = npc.questId!!
            val objective = map.questObjectives.values.single { it.questId == questId }
            initial = ExplorationEngine.place(initial, "p1", npc.position)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 41)

            handler.handle(GameplayWireCommand.WorldAction("quest-accept", "p1", "QUEST_ACCEPT", questId, 999), 1)
            assertEquals(ExplorationQuestStatus.ACTIVE, ExplorationQuestEngine.status(host.state, "p1", questId))

            var movementId = 0
            while (ExplorationEngine.position(host.state, "p1") != objective.position) {
                val current = ExplorationEngine.position(host.state, "p1")
                val direction = if (current.x < objective.position.x) ExplorationDirection.EAST else ExplorationDirection.WEST
                handler.handle(GameplayWireCommand.WorldAction("quest-move-${movementId++}", "p1", "EXPLORE_MOVE", direction.name, 999), 2L + movementId)
            }
            handler.handle(GameplayWireCommand.WorldAction("quest-progress", "p1", "QUEST_PROGRESS", questId, 999), 50)
            assertEquals(ExplorationQuestStatus.OBJECTIVE_COMPLETE, ExplorationQuestEngine.status(host.state, "p1", questId))

            while (ExplorationEngine.position(host.state, "p1") != npc.position) {
                val current = ExplorationEngine.position(host.state, "p1")
                val direction = if (current.x < npc.position.x) ExplorationDirection.EAST else ExplorationDirection.WEST
                handler.handle(GameplayWireCommand.WorldAction("quest-return-${movementId++}", "p1", "EXPLORE_MOVE", direction.name, 999), 60L + movementId)
            }
            val berriesBefore = host.state.partyBerries
            val turnIn = GameplayWireCommand.WorldAction("quest-turn-in", "p1", "QUEST_TURN_IN", questId, 999)
            handler.handle(turnIn, 100)
            handler.handle(turnIn, 101)

            assertEquals(ExplorationQuestStatus.TURNED_IN, ExplorationQuestEngine.status(host.state, "p1", questId))
            assertEquals(berriesBefore + ExplorationQuestEngine.REWARD_BERRIES, host.state.partyBerries)
            assertEquals(1, InventoryEngine.read(host.state, "p1").items[ExplorationQuestEngine.REWARD_ITEM_ID])
        }
    }
}
