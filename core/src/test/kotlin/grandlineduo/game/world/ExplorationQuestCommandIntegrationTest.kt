package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.game.InventoryEngine
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationQuestCommandIntegrationTest {
    fun register() {
        test("authoritative world commands complete a physical quest and reward exactly once") {
            var initial = world("quest-command")
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

        test("P2 completes physical quest over real TCP and converges with host") {
            var initial = world("quest-lan")
            val map = ExplorationEngine.mapFor(initial.campaignId, initial.islandId)
            val npc = map.npcs.values.single { it.questId != null }
            val questId = npc.questId!!
            val objective = map.questObjectives.values.single { it.questId == questId }
            initial = ExplorationEngine.place(initial, "p2", npc.position)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 42)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    client.sendGameplay(GameplayWireCommand.WorldAction("p2-quest-accept", "p2", "QUEST_ACCEPT", questId, 999))
                    assertEquals(host.state, clientReplica.state)

                    var sequence = 0
                    while (ExplorationEngine.position(clientReplica.state, "p2") != objective.position) {
                        val current = ExplorationEngine.position(clientReplica.state, "p2")
                        val direction = if (current.x < objective.position.x) ExplorationDirection.EAST else ExplorationDirection.WEST
                        client.sendGameplay(GameplayWireCommand.WorldAction("p2-quest-out-${sequence++}", "p2", "EXPLORE_MOVE", direction.name, 999))
                    }
                    client.sendGameplay(GameplayWireCommand.WorldAction("p2-quest-progress", "p2", "QUEST_PROGRESS", questId, 999))
                    assertEquals(ExplorationQuestStatus.OBJECTIVE_COMPLETE, ExplorationQuestEngine.status(clientReplica.state, "p2", questId))

                    while (ExplorationEngine.position(clientReplica.state, "p2") != npc.position) {
                        val current = ExplorationEngine.position(clientReplica.state, "p2")
                        val direction = if (current.x < npc.position.x) ExplorationDirection.EAST else ExplorationDirection.WEST
                        client.sendGameplay(GameplayWireCommand.WorldAction("p2-quest-back-${sequence++}", "p2", "EXPLORE_MOVE", direction.name, 999))
                    }
                    client.sendGameplay(GameplayWireCommand.WorldAction("p2-quest-turn-in", "p2", "QUEST_TURN_IN", questId, 999))

                    assertEquals(host.state, clientReplica.state)
                    assertEquals(ExplorationQuestStatus.TURNED_IN, ExplorationQuestEngine.status(host.state, "p2", questId))
                    assertEquals(ExplorationQuestStatus.AVAILABLE, ExplorationQuestEngine.status(host.state, "p1", questId))
                }
            }
        }

        test("P2 accepts defeats and turns in a field boss hunt over real TCP") {
            var initial = WorldState(
                campaignId = "boss-hunt-lan",
                islandId = "meridian-vault",
                partyBerries = 8_000,
                players = mapOf(
                    "p1" to PlayerState("p1", "A", 100_000, 100_000, 0),
                    "p2" to PlayerState("p2", "B", 100_000, 100_000, 0),
                ),
            )
            val map = ExplorationEngine.mapFor(initial.campaignId, initial.islandId)
            val questId = ExplorationQuestEngine.bossHuntQuestId(initial.islandId)
            val hunter = map.npcs.values.single { it.questId == questId }
            val boss = map.enemies.values.single { it.rank == ExplorationEnemyRank.FIELD_BOSS }
            initial = ExplorationEngine.place(initial, "p2", hunter.position)

            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 43)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    client.sendGameplay(
                        GameplayWireCommand.WorldAction("p2-hunt-accept", "p2", "QUEST_ACCEPT", questId, 999)
                    )
                    assertEquals(ExplorationQuestStatus.ACTIVE, ExplorationQuestEngine.status(host.state, "p2", questId))
                    assertEquals(ExplorationQuestStatus.AVAILABLE, ExplorationQuestEngine.status(host.state, "p1", questId))

                    var sequence = 0
                    fun moveP2(direction: ExplorationDirection) {
                        client.sendGameplay(
                            GameplayWireCommand.WorldAction(
                                "p2-hunt-move-${sequence++}",
                                "p2",
                                "EXPLORE_MOVE",
                                direction.name,
                                999,
                            )
                        )
                    }

                    while (ExplorationEngine.position(clientReplica.state, "p2").x > map.spawn.x) moveP2(ExplorationDirection.WEST)
                    while (ExplorationEngine.position(clientReplica.state, "p2").y < boss.position.y) moveP2(ExplorationDirection.SOUTH)
                    while (ExplorationEngine.position(clientReplica.state, "p2").x < boss.position.x) moveP2(ExplorationDirection.EAST)

                    assertTrue(ExplorationCombatEngine.isActive(host.state))
                    var round = 0
                    while (host.state.activeCombat != null && round < 200) {
                        handler.handle(
                            GameplayWireCommand.CombatAction("hunt-p1-${round}", "p1", CombatActionType.ATTACK.name),
                            10_000L + round * 2L,
                        )
                        client.sendGameplay(
                            GameplayWireCommand.CombatAction("hunt-p2-${round}", "p2", CombatActionType.ATTACK.name)
                        )
                        round++
                    }
                    assertEquals(null, host.state.activeCombat)
                    assertEquals(ExplorationQuestStatus.OBJECTIVE_COMPLETE, ExplorationQuestEngine.status(host.state, "p2", questId))
                    assertEquals(ExplorationQuestStatus.AVAILABLE, ExplorationQuestEngine.status(host.state, "p1", questId))

                    while (ExplorationEngine.position(clientReplica.state, "p2").x > map.spawn.x) moveP2(ExplorationDirection.WEST)
                    while (ExplorationEngine.position(clientReplica.state, "p2").y > hunter.position.y) moveP2(ExplorationDirection.NORTH)
                    while (ExplorationEngine.position(clientReplica.state, "p2").x < hunter.position.x) moveP2(ExplorationDirection.EAST)

                    val berriesBeforeTurnIn = host.state.partyBerries
                    val rewardBefore = InventoryEngine.read(host.state, "p2").items[ExplorationQuestEngine.BOSS_REWARD_ITEM_ID] ?: 0
                    client.sendGameplay(
                        GameplayWireCommand.WorldAction("p2-hunt-turn-in", "p2", "QUEST_TURN_IN", questId, 999)
                    )

                    assertEquals(host.state, clientReplica.state)
                    assertEquals(ExplorationQuestStatus.TURNED_IN, ExplorationQuestEngine.status(host.state, "p2", questId))
                    assertEquals(berriesBeforeTurnIn + ExplorationQuestEngine.BOSS_REWARD_BERRIES, host.state.partyBerries)
                    assertEquals(rewardBefore + 1, InventoryEngine.read(host.state, "p2").items[ExplorationQuestEngine.BOSS_REWARD_ITEM_ID])
                    assertEquals(ExplorationQuestStatus.AVAILABLE, ExplorationQuestEngine.status(host.state, "p1", questId))
                }
            }
        }
    }

    private fun world(campaignId: String) = WorldState(
        campaignId = campaignId,
        islandId = "stormglass-cay",
        partyBerries = 2_000,
        players = mapOf(
            "p1" to PlayerState("p1", "A", 30, 30, 0),
            "p2" to PlayerState("p2", "B", 30, 30, 0),
        ),
    )
}
