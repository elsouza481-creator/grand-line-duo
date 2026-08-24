package grandlineduo.game.quest

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.game.InventoryEngine
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcArchetype
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyTelegraph
import grandlineduo.game.duel.DuelPhase
import grandlineduo.game.duel.DuelState
import grandlineduo.game.powers.HakiDiscipline
import grandlineduo.game.powers.HakiState
import grandlineduo.game.powers.HakiType
import grandlineduo.game.powers.PowerTechniqueEngine
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.game.ship.VoyageEncounter
import grandlineduo.game.ship.VoyageIncident
import grandlineduo.game.ship.VoyageIncidentType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object QuestHuntCoordinatorTest {
    fun register() {
        test("active hunt starts authoritative combat from hub without healing") {
            val quest = huntQuest("hunt-start")
            val initial = hubWorld("hunt-start-world", quest, p1Hp = 17, p2Hp = 13)
            val host = HostReplica(initial)
            val coordinator = QuestHuntCoordinator(host, campaignSeed = 81L)

            val event = coordinator.start("hunt-start-command", "p2", quest.questId, 1_000)

            assertEquals(quest.questId, host.state.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG])
            assertEquals(CombatStatus.ACTIVE, host.state.activeCombat!!.status)
            assertEquals(17, host.state.activeCombat!!.players.getValue("p1").hp)
            assertEquals(13, host.state.activeCombat!!.players.getValue("p2").hp)
            assertEquals("START_HUNT", event.payload["meta.questAction"])
            assertEquals(quest.questId, event.payload["meta.questId"])
            assertEquals("1", event.payload["meta.huntEncounter"])
            assertEquals(quest.targetId, event.payload["meta.huntTarget"])
        }

        test("hunt start rejects invalid quest profile health and non hub states") {
            val quest = huntQuest("hunt-start-invalid")
            val base = hubWorld("hunt-invalid-world", quest)
            val progress = base.questBoard.active.getValue(quest.questId)
            val combat = QuestHuntFactory.create(base, progress, 82L)
            val pendingDuel = DuelState("duel", "p1", "p2", DuelPhase.PENDING)
            val voyage = VoyageEncounter(VoyageIncident(VoyageIncidentType.STORM, 1, 9L))
            val nonCompleteArc = ArcState(
                arcId = "arc-open",
                islandId = base.islandId,
                seed = 2L,
                archetype = ArcArchetype.ISLAND_CRISIS,
                phase = ArcPhase.ARRIVAL,
            )
            val noProfile = base.copy(players = base.players + ("p2" to base.players.getValue("p2").copy(profile = null)))
            val zeroHp = base.copy(players = base.players + ("p2" to base.players.getValue("p2").copy(hp = 0)))
            val structuredCombat = base.copy(activeCombat = combat)
            val legacyCombat = StormglassPersistenceAdapter.encode(base, ScenarioState(stage = ScenarioStage.COMPLETE), combat)
            val activeVoyage = base.copy(activeVoyage = voyage)
            val activeDuel = base.copy(activeDuel = pendingDuel)
            val activeArc = base.copy(activeArc = nonCompleteArc)
            val scenarioOpen = StormglassPersistenceAdapter.encode(base, ScenarioState(stage = ScenarioStage.ARRIVAL), null)
            val huntBinding = base.copy(worldFlags = base.worldFlags + (QuestHuntCoordinator.ACTIVE_QUEST_FLAG to quest.questId))
            val bossBinding = base.copy(worldFlags = base.worldFlags + (QuestBossCoordinator.ACTIVE_QUEST_FLAG to "boss"))

            listOf(
                noProfile,
                zeroHp,
                structuredCombat,
                legacyCombat,
                activeVoyage,
                activeDuel,
                activeArc,
                scenarioOpen,
                huntBinding,
                bossBinding,
            ).forEachIndexed { index, invalid ->
                val host = HostReplica(invalid)
                val coordinator = QuestHuntCoordinator(host, campaignSeed = 82L)
                assertTrue(runCatching {
                    coordinator.start("invalid-$index", "p1", quest.questId, 2_000L + index)
                }.isFailure)
                assertEquals(invalid, host.state)
            }

            val nonHunt = quest.copy(questId = "not-hunt", type = QuestType.COLLECT)
            val wrongIsland = quest.copy(questId = "wrong-island", islandId = "elsewhere")
            val ready = QuestProgress(quest, QuestStatus.READY_TO_TURN_IN, quest.requiredAmount, "p1")
            listOf(
                hubWorld("non-hunt", nonHunt),
                hubWorld("wrong-island", wrongIsland, currentIsland = "ironwake-atoll"),
                hubWorld("ready-hunt", quest).copy(
                    questBoard = QuestBoardState(active = mapOf(quest.questId to ready)),
                ),
            ).forEachIndexed { index, invalid ->
                val target = invalid.questBoard.active.values.first().definition
                val host = HostReplica(invalid)
                val coordinator = QuestHuntCoordinator(host, campaignSeed = 82L)
                assertTrue(runCatching {
                    coordinator.start("invalid-quest-$index", "p1", target.questId, 2_100L + index)
                }.isFailure)
                assertEquals(invalid, host.state)
            }
        }

        test("hunt combat locks resolves and command retry is idempotent") {
            val quest = huntQuest("hunt-lock")
            val host = HostReplica(boundWorld("hunt-lock-world", quest, 83L))
            val coordinator = QuestHuntCoordinator(host, campaignSeed = 83L)

            val first = coordinator.submitAction("hunt-lock-p1", "p1", CombatActionType.SETUP, 3_000)
            val retry = coordinator.submitAction("hunt-lock-p1", "p1", CombatActionType.SETUP, 3_001)
            assertEquals(first.eventId, retry.eventId)
            assertEquals(1, host.state.activeCombat!!.lockedActions.size)

            coordinator.submitAction("hunt-lock-p2", "p2", CombatActionType.DEFEND, 3_002)
            assertTrue(host.state.activeCombat == null || host.state.activeCombat!!.round >= 2)
        }

        test("hunt combat uses authoritative equipment modifiers") {
            fun damage(world: WorldState, campaignSeed: Long): Int {
                val host = HostReplica(world)
                val coordinator = QuestHuntCoordinator(host, campaignSeed)
                coordinator.submitAction("loadout-p1-${world.worldFlags.size}", "p1", CombatActionType.ATTACK, 4_000)
                val event = coordinator.submitAction("loadout-p2-${world.worldFlags.size}", "p2", CombatActionType.DEFEND, 4_001)
                return event.payload.getValue("meta.enemyDamage").toInt()
            }

            val quest = huntQuest("hunt-loadout")
            val base = boundWorld("hunt-loadout-world", quest, 84L)
            var equipped = InventoryEngine.grant(base, "p1", "iron_sabre", 1)
            equipped = InventoryEngine.equip(equipped, "p1", "iron_sabre")

            assertEquals(damage(base, 84L) + 4, damage(equipped, 84L))
        }

        test("prepared hunt power spends energy and records use exactly once") {
            val quest = huntQuest("hunt-power")
            val powered = boundWorld("hunt-power-world", quest, 85L, p2Hp = 0, busoshoku = true)
            val prepared = PowerTechniqueEngine.prepare(powered, "p1", "HAKI_BUSOSHOKU")
            val beforeEnergy = powered.players.getValue("p1").energy
            val host = HostReplica(powered)
            val coordinator = QuestHuntCoordinator(host, campaignSeed = 85L)

            val first = coordinator.submitPreparedAction(
                commandId = "hunt-power-command",
                playerId = "p1",
                actionType = prepared.combatAction,
                preparedWorld = prepared.world,
                sourceFingerprint = "power|p1|HAKI_BUSOSHOKU",
                metadata = mapOf("meta.powerTechnique" to "HAKI_BUSOSHOKU"),
                hostTimestamp = 5_000,
            )
            val retry = coordinator.submitPreparedAction(
                commandId = "hunt-power-command",
                playerId = "p1",
                actionType = prepared.combatAction,
                preparedWorld = prepared.world,
                sourceFingerprint = "power|p1|HAKI_BUSOSHOKU",
                metadata = mapOf("meta.powerTechnique" to "HAKI_BUSOSHOKU"),
                hostTimestamp = 5_001,
            )

            assertEquals(first.eventId, retry.eventId)
            assertEquals(beforeEnergy - 4, host.state.players.getValue("p1").energy)
            assertEquals(
                1,
                host.state.players.getValue("p1").profile!!.haki.disciplines.getValue(HakiType.BUSOSHOKU).useCount,
            )
        }

        test("hunt victory advances only bound contract without granting rewards") {
            val quest = huntQuest("hunt-victory", reward = QuestReward(berries = 9_000))
            val sibling = huntQuest("hunt-sibling", reward = QuestReward(berries = 7_000))
            var base = boundWorld("hunt-victory-world", quest, 86L)
            base = base.copy(
                questBoard = base.questBoard.copy(
                    active = base.questBoard.active + (
                        sibling.questId to QuestProgress(sibling, QuestStatus.ACTIVE, 0, "p2")
                    ),
                ),
                activeCombat = base.activeCombat!!.copy(
                    enemy = base.activeCombat.enemy.copy(hp = 1),
                ),
            )
            val berriesBefore = base.partyBerries
            val bountiesBefore = base.players.mapValues { it.value.bounty }
            val flagsBefore = base.worldFlags.filterKeys { it != QuestHuntCoordinator.ACTIVE_QUEST_FLAG }
            val host = HostReplica(base)
            val coordinator = QuestHuntCoordinator(host, campaignSeed = 86L)

            coordinator.submitAction("hunt-win-p1", "p1", CombatActionType.SETUP, 6_000)
            val event = coordinator.submitAction("hunt-win-p2", "p2", CombatActionType.FINISHER, 6_001)

            assertEquals(null, host.state.activeCombat)
            assertEquals(null, host.state.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG])
            assertEquals(1, host.state.questBoard.active.getValue(quest.questId).progress)
            assertEquals(QuestStatus.ACTIVE, host.state.questBoard.active.getValue(quest.questId).status)
            assertEquals(0, host.state.questBoard.active.getValue(sibling.questId).progress)
            assertEquals(berriesBefore, host.state.partyBerries)
            assertEquals(bountiesBefore, host.state.players.mapValues { it.value.bounty })
            assertEquals(flagsBefore, host.state.worldFlags)
            assertEquals("ENEMY_DEFEATED", event.payload["meta.questObjective"])
            assertEquals(quest.questId, event.payload["meta.questObjectiveSourceQuest"])
            assertEquals(quest.targetId, event.payload["meta.questObjectiveTarget"])
            assertEquals("1", event.payload["meta.questObjectiveAmount"])
            assertEquals("1", event.payload["meta.questProgress"])
        }

        test("three common hunt encounter outcomes progress active active then ready") {
            listOf(
                0 to (1 to QuestStatus.ACTIVE),
                1 to (2 to QuestStatus.ACTIVE),
                2 to (3 to QuestStatus.READY_TO_TURN_IN),
            ).forEach { (startingProgress, expected) ->
                val quest = huntQuest("hunt-wave-$startingProgress")
                val base = boundWorld("hunt-wave-world-$startingProgress", quest, 87L, progress = startingProgress)
                val weak = base.copy(
                    activeCombat = base.activeCombat!!.copy(enemy = base.activeCombat.enemy.copy(hp = 1)),
                )
                val host = HostReplica(weak)
                val coordinator = QuestHuntCoordinator(host, campaignSeed = 87L)

                coordinator.submitAction("wave-$startingProgress-p1", "p1", CombatActionType.SETUP, 7_000L + startingProgress)
                coordinator.submitAction("wave-$startingProgress-p2", "p2", CombatActionType.FINISHER, 7_100L + startingProgress)

                val after = host.state.questBoard.active.getValue(quest.questId)
                assertEquals(expected.first, after.progress)
                assertEquals(expected.second, after.status)
            }
        }

        test("hunt defeat permanently fails contract preserves terminal combat and grants no reward") {
            val quest = huntQuest("hunt-defeat", reward = QuestReward(berries = 12_000))
            val base = boundWorld("hunt-defeat-world", quest, 88L)
            val doomed = base.activeCombat!!.copy(
                players = mapOf(
                    "p1" to Combatant("p1", "Kairo", 1, base.players.getValue("p1").maxHp),
                    "p2" to Combatant("p2", "Namiya", 0, base.players.getValue("p2").maxHp),
                ),
                enemy = base.activeCombat.enemy.copy(hp = 200, maxHp = 200, attackPower = 28),
                telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
            )
            val initial = base.copy(activeCombat = doomed)
            val berriesBefore = initial.partyBerries
            val host = HostReplica(initial)
            val coordinator = QuestHuntCoordinator(host, campaignSeed = 88L)

            val event = coordinator.submitAction("hunt-last-stand", "p1", CombatActionType.ATTACK, 8_000)

            assertEquals(CombatStatus.DEFEAT, host.state.activeCombat!!.status)
            assertEquals(null, host.state.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG])
            assertTrue(quest.questId !in host.state.questBoard.active)
            assertTrue(quest.questId in host.state.questBoard.failedQuestIds)
            assertEquals(berriesBefore, host.state.partyBerries)
            assertEquals("HUNT_DEFEAT", event.payload["meta.questFailure"])
        }

        test("invalid bound hunt origin rejects combat without fallback mutation") {
            val quest = huntQuest("hunt-invalid-binding")
            val valid = boundWorld("hunt-invalid-binding-world", quest, 89L)
            val invalidStates = listOf(
                valid.copy(questBoard = valid.questBoard.copy(active = emptyMap())),
                valid.copy(
                    questBoard = valid.questBoard.copy(
                        active = mapOf(
                            quest.questId to valid.questBoard.active.getValue(quest.questId).copy(
                                definition = quest.copy(type = QuestType.COLLECT),
                            )
                        ),
                    ),
                ),
                valid.copy(
                    questBoard = valid.questBoard.copy(
                        active = mapOf(
                            quest.questId to valid.questBoard.active.getValue(quest.questId).copy(
                                status = QuestStatus.READY_TO_TURN_IN,
                            )
                        ),
                    ),
                ),
            )

            invalidStates.forEachIndexed { index, invalid ->
                val host = HostReplica(invalid)
                val coordinator = QuestHuntCoordinator(host, campaignSeed = 89L)
                assertTrue(runCatching {
                    coordinator.submitAction("invalid-bound-$index", "p1", CombatActionType.ATTACK, 9_000L + index)
                }.isFailure)
                assertEquals(invalid, host.state)
            }
        }
    }

    private fun hubWorld(
        id: String,
        quest: QuestDefinition,
        p1Hp: Int = 30,
        p2Hp: Int = 28,
        currentIsland: String = quest.islandId,
        progress: Int = 0,
        busoshoku: Boolean = false,
    ): WorldState {
        val p1Profile0 = profile("Kairo")
        val p1Profile = if (busoshoku) {
            p1Profile0.copy(haki = HakiState(disciplines = mapOf(HakiType.BUSOSHOKU to HakiDiscipline(2))))
        } else p1Profile0
        val p2Profile = profile("Namiya")
        val base = WorldState(
            campaignId = id,
            islandId = currentIsland,
            partyBerries = 2_000,
            players = mapOf(
                "p1" to PlayerState(
                    "p1", p1Profile.name, p1Hp, p1Profile.maxHp, 9_000_000L,
                    p1Profile.maxEnergy, p1Profile.maxEnergy, p1Profile,
                ),
                "p2" to PlayerState(
                    "p2", p2Profile.name, p2Hp, p2Profile.maxHp, 8_000_000L,
                    p2Profile.maxEnergy, p2Profile.maxEnergy, p2Profile,
                ),
            ),
            questBoard = QuestBoardState(
                active = mapOf(
                    quest.questId to QuestProgress(
                        definition = quest,
                        status = QuestStatus.ACTIVE,
                        progress = progress,
                        acceptedBy = "p1",
                    )
                ),
            ),
        )
        return StormglassPersistenceAdapter.encode(base, ScenarioState(stage = ScenarioStage.COMPLETE), null)
    }

    private fun boundWorld(
        id: String,
        quest: QuestDefinition,
        campaignSeed: Long,
        progress: Int = 0,
        p2Hp: Int = 28,
        busoshoku: Boolean = false,
    ): WorldState {
        val world = hubWorld(id, quest, p2Hp = p2Hp, progress = progress, busoshoku = busoshoku)
        val active = world.questBoard.active.getValue(quest.questId)
        return world.copy(
            activeCombat = QuestHuntFactory.create(world, active, campaignSeed),
            worldFlags = world.worldFlags + (QuestHuntCoordinator.ACTIVE_QUEST_FLAG to quest.questId),
        )
    }

    private fun profile(name: String) =
        (CharacterCreation.create(CharacterCreationTest.validDraft().copy(name = name)) as CharacterCreationResult.Success).profile

    private fun huntQuest(
        id: String,
        reward: QuestReward = QuestReward(berries = 1_500),
    ) = QuestDefinition(
        questId = id,
        islandId = "ironwake-atoll",
        title = "Caçada aos saqueadores do cais",
        type = QuestType.HUNT,
        rarity = QuestRarity.COMMON,
        issuerFaction = "CIVILIANS",
        targetId = "dock-raiders",
        requiredAmount = 3,
        reward = reward,
    )
}
