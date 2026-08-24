package grandlineduo.game.quest

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcArchetype
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph
import grandlineduo.game.duel.DuelPhase
import grandlineduo.game.duel.DuelState
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.game.ship.VoyageEncounter
import grandlineduo.game.ship.VoyageIncident
import grandlineduo.game.ship.VoyageIncidentType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object QuestFieldCoordinatorTest {
    fun register() {
        test("valid explore attempt spends one energy records state and emits exact progress on success") {
            val quest = exploreQuest("field-explore", reward = QuestReward(berries = 9_000))
            val initial = hubWorld(quest, p1Energy = 7)
            val progress = initial.questBoard.active.getValue(quest.questId)
            val seed = seedFor(initial, progress, "p1", QuestFieldActionType.EXPLORE_SITE, true)
            val host = HostReplica(initial)
            val berriesBefore = initial.partyBerries
            val bountiesBefore = initial.players.mapValues { it.value.bounty }

            val event = QuestFieldCoordinator(host, seed).attempt(
                "field-explore-cmd",
                "p1",
                quest.questId,
                QuestFieldActionType.EXPLORE_SITE,
                1_000,
            )

            assertEquals(6, host.state.players.getValue("p1").energy)
            assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
            assertEquals(1, host.state.questBoard.active.getValue(quest.questId).progress)
            assertEquals(QuestStatus.ACTIVE, host.state.questBoard.active.getValue(quest.questId).status)
            assertEquals(berriesBefore, host.state.partyBerries)
            assertEquals(bountiesBefore, host.state.players.mapValues { it.value.bounty })
            assertEquals("EXPLORE_SITE", event.payload["meta.questFieldAction"])
            assertEquals(quest.questId, event.payload["meta.questId"])
            assertEquals("1", event.payload["meta.questFieldAttempt"])
            assertEquals("p1", event.payload["meta.questFieldActor"])
            assertEquals("true", event.payload["meta.questFieldSuccess"])
            assertEquals("1", event.payload["meta.questFieldEnergySpent"])
            assertEquals("LOCATION_VISITED", event.payload["meta.questObjective"])
            assertEquals(quest.questId, event.payload["meta.questObjectiveSourceQuest"])
            assertEquals(quest.targetId, event.payload["meta.questObjectiveTarget"])
            assertEquals("1", event.payload["meta.questObjectiveAmount"])
            assertEquals("1", event.payload["meta.questProgress"])
            val last = QuestFieldState.readLast(host.state, quest.questId)!!
            assertEquals("p1", last.actorId)
            assertEquals(QuestFieldActionType.EXPLORE_SITE, last.actionType)
            assertEquals(true, last.success)
        }

        test("failed collect attempt spends one energy records roll and gives zero progress") {
            val quest = collectQuest("field-collect-fail")
            val initial = hubWorld(quest, p2Energy = 6)
            val progress = initial.questBoard.active.getValue(quest.questId)
            val seed = seedFor(initial, progress, "p2", QuestFieldActionType.SEARCH_SUPPLIES, false)
            val host = HostReplica(initial)

            val event = QuestFieldCoordinator(host, seed).attempt(
                "field-collect-fail-cmd",
                "p2",
                quest.questId,
                QuestFieldActionType.SEARCH_SUPPLIES,
                2_000,
            )

            assertEquals(5, host.state.players.getValue("p2").energy)
            assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
            assertEquals(0, host.state.questBoard.active.getValue(quest.questId).progress)
            assertEquals("false", event.payload["meta.questFieldSuccess"])
            assertEquals(null, event.payload["meta.questObjective"])
            val last = QuestFieldState.readLast(host.state, quest.questId)!!
            assertTrue(last.roll in 1..20)
            assertEquals(false, last.success)
        }

        test("bound field success advances only the selected same target quest") {
            val primary = exploreQuest("field-primary")
            val sibling = exploreQuest("field-sibling")
            val base = hubWorld(primary).copy(
                questBoard = QuestBoardState(
                    active = mapOf(
                        primary.questId to QuestProgress(primary, QuestStatus.ACTIVE, 0, "p1"),
                        sibling.questId to QuestProgress(sibling, QuestStatus.ACTIVE, 0, "p2"),
                    ),
                ),
            )
            val progress = base.questBoard.active.getValue(primary.questId)
            val seed = seedFor(base, progress, "p1", QuestFieldActionType.EXPLORE_SITE, true)
            val host = HostReplica(base)

            QuestFieldCoordinator(host, seed).attempt(
                "field-isolation",
                "p1",
                primary.questId,
                QuestFieldActionType.EXPLORE_SITE,
                3_000,
            )

            assertEquals(1, host.state.questBoard.active.getValue(primary.questId).progress)
            assertEquals(0, host.state.questBoard.active.getValue(sibling.questId).progress)
        }

        test("p2 may perform a field action on a contract accepted by p1") {
            val quest = collectQuest("field-either-player")
            val initial = hubWorld(quest, acceptedBy = "p1", p2Energy = 8)
            val progress = initial.questBoard.active.getValue(quest.questId)
            val seed = seedFor(initial, progress, "p2", QuestFieldActionType.SEARCH_SUPPLIES, true)
            val host = HostReplica(initial)

            QuestFieldCoordinator(host, seed).attempt(
                "field-p2",
                "p2",
                quest.questId,
                QuestFieldActionType.SEARCH_SUPPLIES,
                4_000,
            )

            assertEquals(7, host.state.players.getValue("p2").energy)
            assertEquals(1, host.state.questBoard.active.getValue(quest.questId).progress)
            assertEquals("p2", QuestFieldState.readLast(host.state, quest.questId)!!.actorId)
        }

        test("field command retry is idempotent and does not spend energy or ordinal twice") {
            val quest = collectQuest("field-retry")
            val initial = hubWorld(quest, p2Energy = 9)
            val progress = initial.questBoard.active.getValue(quest.questId)
            val seed = seedFor(initial, progress, "p2", QuestFieldActionType.SEARCH_SUPPLIES, true)
            val host = HostReplica(initial)
            val coordinator = QuestFieldCoordinator(host, seed)

            val first = coordinator.attempt(
                "same-command",
                "p2",
                quest.questId,
                QuestFieldActionType.SEARCH_SUPPLIES,
                5_000,
            )
            val afterFirst = host.state
            val retry = coordinator.attempt(
                "same-command",
                "p2",
                quest.questId,
                QuestFieldActionType.SEARCH_SUPPLIES,
                5_001,
            )

            assertEquals(first.eventId, retry.eventId)
            assertEquals(afterFirst, host.state)
            assertEquals(8, host.state.players.getValue("p2").energy)
            assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
        }

        test("field command id collision rejects without second mutation") {
            val explore = exploreQuest("field-collision-explore")
            val collect = collectQuest("field-collision-collect")
            val initial = hubWorld(explore).copy(
                questBoard = QuestBoardState(
                    active = mapOf(
                        explore.questId to QuestProgress(explore, QuestStatus.ACTIVE, 0, "p1"),
                        collect.questId to QuestProgress(collect, QuestStatus.ACTIVE, 0, "p1"),
                    ),
                ),
            )
            val seed = seedFor(
                initial,
                initial.questBoard.active.getValue(explore.questId),
                "p1",
                QuestFieldActionType.EXPLORE_SITE,
                true,
            )
            val host = HostReplica(initial)
            val coordinator = QuestFieldCoordinator(host, seed)
            coordinator.attempt("collision", "p1", explore.questId, QuestFieldActionType.EXPLORE_SITE, 6_000)
            val afterFirst = host.state

            val result = runCatching {
                coordinator.attempt("collision", "p1", collect.questId, QuestFieldActionType.SEARCH_SUPPLIES, 6_001)
            }

            assertTrue(result.isFailure)
            assertEquals(afterFirst, host.state)
        }

        test("final successful field attempt transitions contract to ready") {
            val quest = exploreQuest("field-ready")
            val initial = hubWorld(quest, progress = 2)
            val current = initial.questBoard.active.getValue(quest.questId)
            val seed = seedFor(initial, current, "p1", QuestFieldActionType.EXPLORE_SITE, true)
            val host = HostReplica(initial)

            QuestFieldCoordinator(host, seed).attempt(
                "field-ready-cmd",
                "p1",
                quest.questId,
                QuestFieldActionType.EXPLORE_SITE,
                7_000,
            )

            val after = host.state.questBoard.active.getValue(quest.questId)
            assertEquals(3, after.progress)
            assertEquals(QuestStatus.READY_TO_TURN_IN, after.status)
        }

        test("field attempt rejects invalid profiles health energy quest and gameplay states without mutation") {
            val quest = exploreQuest("field-invalid")
            val base = hubWorld(quest)
            val progress = base.questBoard.active.getValue(quest.questId)
            val ready = progress.copy(status = QuestStatus.READY_TO_TURN_IN, progress = quest.requiredAmount)
            val voyage = VoyageEncounter(VoyageIncident(VoyageIncidentType.STORM, 1, 9L))
            val duel = DuelState("field-duel", "p1", "p2", DuelPhase.PENDING)
            val openArc = ArcState(
                arcId = "field-open-arc",
                islandId = base.islandId,
                seed = 4L,
                archetype = ArcArchetype.ISLAND_CRISIS,
                phase = ArcPhase.ARRIVAL,
            )
            val invalidStates = listOf(
                base.copy(players = base.players + ("p2" to base.players.getValue("p2").copy(profile = null))),
                base.copy(players = base.players + ("p1" to base.players.getValue("p1").copy(hp = 0))),
                base.copy(players = base.players + ("p2" to base.players.getValue("p2").copy(hp = 0))),
                base.copy(players = base.players + ("p1" to base.players.getValue("p1").copy(energy = 0))),
                base.copy(islandId = "other-island"),
                base.copy(questBoard = QuestBoardState(active = mapOf(quest.questId to ready))),
                base.copy(activeCombat = dummyCombat()),
                StormglassPersistenceAdapter.encode(base, ScenarioState(stage = ScenarioStage.COMPLETE), dummyCombat()),
                base.copy(activeVoyage = voyage),
                base.copy(activeDuel = duel),
                base.copy(worldFlags = base.worldFlags + (QuestHuntCoordinator.ACTIVE_QUEST_FLAG to "hunt")),
                base.copy(worldFlags = base.worldFlags + (QuestBossCoordinator.ACTIVE_QUEST_FLAG to "boss")),
                base.copy(activeArc = openArc),
                StormglassPersistenceAdapter.encode(base, ScenarioState(stage = ScenarioStage.ARRIVAL), null),
            )

            invalidStates.forEachIndexed { index, invalid ->
                val host = HostReplica(invalid)
                val result = runCatching {
                    QuestFieldCoordinator(host, 91L).attempt(
                        "field-invalid-$index",
                        "p1",
                        quest.questId,
                        QuestFieldActionType.EXPLORE_SITE,
                        8_000L + index,
                    )
                }
                assertTrue(result.isFailure, "invalid state $index must reject")
                assertEquals(invalid, host.state)
            }

            val mismatchHost = HostReplica(base)
            val mismatch = runCatching {
                QuestFieldCoordinator(mismatchHost, 91L).attempt(
                    "field-invalid-action",
                    "p1",
                    quest.questId,
                    QuestFieldActionType.SEARCH_SUPPLIES,
                    8_100,
                )
            }
            assertTrue(mismatch.isFailure)
            assertEquals(base, mismatchHost.state)
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

    private fun hubWorld(
        quest: QuestDefinition,
        p1Energy: Int = 12,
        p2Energy: Int = 12,
        progress: Int = 0,
        acceptedBy: String = "p1",
    ): WorldState {
        val p1Profile = profile("Kairo")
        val p2Profile = profile("Namiya")
        val base = WorldState(
            campaignId = "field-coordinator-${quest.questId}",
            islandId = quest.islandId,
            partyBerries = 2_000,
            players = mapOf(
                "p1" to PlayerState("p1", p1Profile.name, p1Profile.maxHp, p1Profile.maxHp, 2_000_000L, p1Energy, p1Profile.maxEnergy, p1Profile),
                "p2" to PlayerState("p2", p2Profile.name, p2Profile.maxHp, p2Profile.maxHp, 3_000_000L, p2Energy, p2Profile.maxEnergy, p2Profile),
            ),
            questBoard = QuestBoardState(
                active = mapOf(
                    quest.questId to QuestProgress(quest, QuestStatus.ACTIVE, progress, acceptedBy),
                ),
            ),
        )
        return StormglassPersistenceAdapter.encode(base, ScenarioState(stage = ScenarioStage.COMPLETE), null)
    }

    private fun profile(name: String) =
        (CharacterCreation.create(CharacterCreationTest.validDraft().copy(name = name)) as CharacterCreationResult.Success).profile

    private fun exploreQuest(id: String, reward: QuestReward = QuestReward()) = QuestDefinition(
        questId = id,
        islandId = "shells-town",
        title = "Cartografar ruínas esquecidas",
        type = QuestType.EXPLORE,
        rarity = QuestRarity.COMMON,
        issuerFaction = "CIVILIANS",
        targetId = "forgotten-ruins",
        requiredAmount = 3,
        reward = reward,
    )

    private fun collectQuest(id: String, reward: QuestReward = QuestReward()) = QuestDefinition(
        questId = id,
        islandId = "shells-town",
        title = "Reunir suprimentos de emergência",
        type = QuestType.COLLECT,
        rarity = QuestRarity.COMMON,
        issuerFaction = "CIVILIANS",
        targetId = "medical-supplies",
        requiredAmount = 4,
        reward = reward,
    )

    private fun dummyCombat() = CombatState(
        round = 1,
        players = mapOf(
            "p1" to Combatant("p1", "Kairo", 30, 30),
            "p2" to Combatant("p2", "Namiya", 30, 30),
        ),
        enemy = EnemyCombatant("field-enemy", "Field Enemy", 40, 40, 8),
        telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
        status = CombatStatus.ACTIVE,
    )
}
