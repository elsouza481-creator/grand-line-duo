package grandlineduo.game.quest

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.InventoryEngine
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object QuestFieldRoutingTest {
    fun register() {
        test("handler routes explore site through existing quest action") {
            val quest = fieldQuest("route-explore", QuestType.EXPLORE)
            val initial = hubWorld(quest)
            val seed = seedFor(initial, initial.questBoard.active.getValue(quest.questId), "p2", QuestFieldActionType.EXPLORE_SITE, true)
            val host = HostReplica(initial)

            val event = StormglassGameplayCommandHandler(host, seed).handle(
                GameplayWireCommand.QuestAction("route-explore-cmd", "p2", "EXPLORE_SITE", quest.questId, 1),
                1_000,
            )

            assertEquals("EXPLORE_SITE", event.payload["meta.questFieldAction"])
            assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
            assertEquals(1, host.state.questBoard.active.getValue(quest.questId).progress)
        }

        test("handler routes search supplies through existing quest action") {
            val quest = fieldQuest("route-collect", QuestType.COLLECT)
            val initial = hubWorld(quest)
            val seed = seedFor(initial, initial.questBoard.active.getValue(quest.questId), "p1", QuestFieldActionType.SEARCH_SUPPLIES, true)
            val host = HostReplica(initial)

            val event = StormglassGameplayCommandHandler(host, seed).handle(
                GameplayWireCommand.QuestAction("route-collect-cmd", "p1", "SEARCH_SUPPLIES", quest.questId, 1),
                2_000,
            )

            assertEquals("SEARCH_SUPPLIES", event.payload["meta.questFieldAction"])
            assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
            assertEquals(1, host.state.questBoard.active.getValue(quest.questId).progress)
        }

        test("handler rejects field amount other than one without mutation") {
            val quest = fieldQuest("route-amount", QuestType.EXPLORE)
            val initial = hubWorld(quest)
            val host = HostReplica(initial)

            val result = runCatching {
                StormglassGameplayCommandHandler(host, 701L).handle(
                    GameplayWireCommand.QuestAction("route-amount-cmd", "p1", "EXPLORE_SITE", quest.questId, 2),
                    3_000,
                )
            }

            assertTrue(result.isFailure)
            assertEquals(initial, host.state)
        }

        test("handler retry of field command keeps coordinator idempotency") {
            val quest = fieldQuest("route-retry", QuestType.COLLECT)
            val initial = hubWorld(quest)
            val seed = seedFor(initial, initial.questBoard.active.getValue(quest.questId), "p2", QuestFieldActionType.SEARCH_SUPPLIES, true)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed)
            val command = GameplayWireCommand.QuestAction("route-retry-cmd", "p2", "SEARCH_SUPPLIES", quest.questId, 1)
            val energyBefore = initial.players.getValue("p2").energy

            val first = handler.handle(command, 4_000)
            val afterFirst = host.state
            val retry = handler.handle(command, 4_001)

            assertEquals(first.eventId, retry.eventId)
            assertEquals(afterFirst, host.state)
            assertEquals(energyBefore - 1, host.state.players.getValue("p2").energy)
            assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
        }

        test("handler rejects manual explore and collect while remaining migration progress routes") {
            listOf(QuestType.EXPLORE, QuestType.COLLECT).forEach { type ->
                val quest = fieldQuest("route-manual-${type.name.lowercase()}", type)
                val initial = hubWorld(quest)
                val host = HostReplica(initial)
                val result = runCatching {
                    StormglassGameplayCommandHandler(host, 702L).handle(
                        GameplayWireCommand.QuestAction("route-manual-${type.name}", "p1", "PROGRESS", quest.questId, 1),
                        5_000,
                    )
                }
                assertTrue(result.isFailure)
                assertEquals(initial, host.state)
            }

            listOf(QuestType.RESCUE, QuestType.ESCORT, QuestType.INVESTIGATE).forEach { type ->
                val quest = fieldQuest("route-keep-${type.name.lowercase()}", type)
                val host = HostReplica(hubWorld(quest))
                StormglassGameplayCommandHandler(host, 703L).handle(
                    GameplayWireCommand.QuestAction("route-keep-${type.name}", "p1", "PROGRESS", quest.questId, 1),
                    5_100,
                )
                assertEquals(1, host.state.questBoard.active.getValue(quest.questId).progress)
            }
        }

        test("shop buy and inventory grant do not progress collect contract") {
            val quest = fieldQuest("route-no-shop", QuestType.COLLECT)
            val initial = hubWorld(quest).copy(partyBerries = 10_000)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, 704L)

            handler.handle(
                GameplayWireCommand.WorldAction("route-buy", "p1", "SHOP_BUY", "bandage", 1),
                6_000,
            )

            assertEquals(0, host.state.questBoard.active.getValue(quest.questId).progress)
            val granted = InventoryEngine.grant(host.state, "p1", "bandage", 3)
            assertEquals(0, granted.questBoard.active.getValue(quest.questId).progress)
        }

        test("ordinary scenario choice does not progress explore contract") {
            val quest = fieldQuest("route-no-story", QuestType.EXPLORE).copy(islandId = "stormglass-cay")
            val base = profileWorld("route-story", "stormglass-cay").copy(
                questBoard = QuestBoardState(
                    active = mapOf(quest.questId to QuestProgress(quest, QuestStatus.ACTIVE, 0, "p1")),
                ),
            )
            val initial = StormglassPersistenceAdapter.encode(base, ScenarioState(stage = ScenarioStage.ARRIVAL), null)
            val host = HostReplica(initial)

            StormglassGameplayCommandHandler(host, 705L).handle(
                GameplayWireCommand.ScenarioChoice("route-story-choice", "p1", "help_dockworker"),
                7_000,
            )

            assertEquals(0, host.state.questBoard.active.getValue(quest.questId).progress)
        }

        test("field routing preserves hunt and boss start behavior") {
            val hunt = fieldQuest("route-hunt-regression", QuestType.HUNT)
            val huntHost = HostReplica(hubWorld(hunt))
            StormglassGameplayCommandHandler(huntHost, 706L).handle(
                GameplayWireCommand.QuestAction("route-hunt-start", "p1", "START_HUNT", hunt.questId, 1),
                8_000,
            )
            assertEquals(hunt.questId, huntHost.state.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG])
            assertEquals(CombatStatus.ACTIVE, huntHost.state.activeCombat!!.status)

            val boss = fieldQuest("route-boss-regression", QuestType.BOSS)
            val bossHost = HostReplica(hubWorld(boss))
            StormglassGameplayCommandHandler(bossHost, 707L).handle(
                GameplayWireCommand.QuestAction("route-boss-start", "p2", "START_BOSS", boss.questId, 1),
                8_100,
            )
            assertEquals(boss.questId, bossHost.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
            assertEquals(CombatStatus.ACTIVE, bossHost.state.activeCombat!!.status)
        }
    }

    private fun seedFor(
        world: WorldState,
        progress: QuestProgress,
        actorId: String,
        action: QuestFieldActionType,
        wantSuccess: Boolean,
    ): Long = (1L..10_000L).first { seed ->
        QuestFieldResolver.resolve(world, progress, actorId, action, 1, seed).success == wantSuccess
    }

    private fun hubWorld(quest: QuestDefinition): WorldState {
        val base = profileWorld("field-routing-${quest.questId}", quest.islandId).copy(
            questBoard = QuestBoardState(
                active = mapOf(quest.questId to QuestProgress(quest, QuestStatus.ACTIVE, 0, "p1")),
            ),
        )
        return StormglassPersistenceAdapter.encode(base, ScenarioState(stage = ScenarioStage.COMPLETE), null)
    }

    private fun profileWorld(id: String, islandId: String): WorldState {
        val p1 = profile("Kairo")
        val p2 = profile("Namiya")
        return WorldState(
            campaignId = id,
            islandId = islandId,
            partyBerries = 10_000,
            players = mapOf(
                "p1" to PlayerState("p1", p1.name, p1.maxHp, p1.maxHp, 0, p1.maxEnergy, p1.maxEnergy, p1),
                "p2" to PlayerState("p2", p2.name, p2.maxHp, p2.maxHp, 0, p2.maxEnergy, p2.maxEnergy, p2),
            ),
        )
    }

    private fun profile(name: String) =
        (CharacterCreation.create(CharacterCreationTest.validDraft().copy(name = name)) as CharacterCreationResult.Success).profile

    private fun fieldQuest(id: String, type: QuestType): QuestDefinition {
        val (target, required) = when (type) {
            QuestType.EXPLORE -> "forgotten-ruins" to 3
            QuestType.COLLECT -> "medical-supplies" to 4
            QuestType.HUNT -> "dock-raiders" to 3
            QuestType.BOSS -> "island-enforcer" to 1
            QuestType.RESCUE -> "captured-sailor" to 2
            QuestType.ESCORT -> "merchant-convoy" to 2
            QuestType.INVESTIGATE -> "smuggler-ledger" to 2
        }
        return QuestDefinition(
            questId = id,
            islandId = "shells-town",
            title = "Contrato $id",
            type = type,
            rarity = QuestRarity.COMMON,
            issuerFaction = "CIVILIANS",
            targetId = target,
            requiredAmount = required,
        )
    }
}
