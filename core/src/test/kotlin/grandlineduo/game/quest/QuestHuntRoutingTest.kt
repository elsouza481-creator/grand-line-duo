package grandlineduo.game.quest

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcArchetype
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.powers.HakiDiscipline
import grandlineduo.game.powers.HakiType
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object QuestHuntRoutingTest {
    fun register() {
        test("gameplay handler starts hunt through existing quest wire action") {
            val quest = hunt("routing-start")
            val host = HostReplica(hubWorld("routing-start-world", quest))
            val handler = StormglassGameplayCommandHandler(host, seed = 301L)

            val event = handler.handle(
                GameplayWireCommand.QuestAction("routing-start-command", "p2", "START_HUNT", quest.questId),
                1_000,
            )

            assertEquals(quest.questId, host.state.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG])
            assertEquals(CombatStatus.ACTIVE, host.state.activeCombat!!.status)
            assertEquals("START_HUNT", event.payload["meta.questAction"])
        }

        test("gameplay handler rejects manual hunt progress without mutation") {
            val quest = hunt("routing-manual")
            val initial = hubWorld("routing-manual-world", quest)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 302L)

            val result = runCatching {
                handler.handle(
                    GameplayWireCommand.QuestAction("routing-manual-command", "p1", "PROGRESS", quest.questId, 1),
                    2_000,
                )
            }

            assertTrue(result.isFailure)
            assertEquals(initial, host.state)
        }

        test("gameplay handler routes hunt bound basic combat before arc combat") {
            val quest = hunt("routing-basic")
            val world = boundHuntWorld("routing-basic-world", quest, 303L)
            val host = HostReplica(world)
            val handler = StormglassGameplayCommandHandler(host, seed = 303L)

            handler.handle(
                GameplayWireCommand.CombatAction("routing-basic-action", "p1", CombatActionType.SETUP.name),
                3_000,
            )

            assertEquals(CombatActionType.SETUP, host.state.activeCombat!!.lockedActions.getValue("p1").type)
            assertEquals(quest.questId, host.state.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG])
        }

        test("gameplay handler routes prepared Haki power into bound hunt atomically") {
            val quest = hunt("routing-power")
            val base = boundHuntWorld("routing-power-world", quest, 304L, busoshoku = true)
            val beforeEnergy = base.players.getValue("p1").energy
            val host = HostReplica(base)
            val handler = StormglassGameplayCommandHandler(host, seed = 304L)

            val event = handler.handle(
                GameplayWireCommand.PowerAction("routing-power-command", "p1", "HAKI_BUSOSHOKU"),
                4_000,
            )

            assertEquals(beforeEnergy - 4, host.state.players.getValue("p1").energy)
            assertEquals(
                1,
                host.state.players.getValue("p1").profile!!.haki.disciplines.getValue(HakiType.BUSOSHOKU).useCount,
            )
            assertEquals("HAKI_BUSOSHOKU", event.payload["meta.powerTechnique"])
            assertEquals(quest.questId, event.payload["meta.huntQuestId"])
        }

        test("gameplay handler rejects simultaneous hunt and boss combat bindings") {
            val quest = hunt("routing-overlap")
            val base = boundHuntWorld("routing-overlap-world", quest, 305L)
            val invalid = base.copy(
                worldFlags = base.worldFlags + (QuestBossCoordinator.ACTIVE_QUEST_FLAG to "some-boss"),
            )
            val host = HostReplica(invalid)
            val handler = StormglassGameplayCommandHandler(host, seed = 305L)

            val result = runCatching {
                handler.handle(
                    GameplayWireCommand.CombatAction("routing-overlap-action", "p1", CombatActionType.ATTACK.name),
                    5_000,
                )
            }

            assertTrue(result.isFailure)
            assertEquals(invalid, host.state)
        }

        test("gameplay handler preserves quest boss start routing") {
            val quest = boss("routing-boss")
            val host = HostReplica(hubWorld("routing-boss-world", quest))
            val handler = StormglassGameplayCommandHandler(host, seed = 306L)

            handler.handle(
                GameplayWireCommand.QuestAction("routing-boss-start", "p1", "START_BOSS", quest.questId),
                6_000,
            )

            assertEquals(quest.questId, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
            assertEquals(quest.targetId, host.state.activeCombat!!.enemy.id)
        }

        test("gameplay handler preserves ordinary structured arc combat routing") {
            val base = profileWorld("routing-arc").copy(
                activeArc = ArcState(
                    arcId = "arc-routing",
                    islandId = "ironwake-atoll",
                    seed = 307L,
                    archetype = ArcArchetype.ISLAND_CRISIS,
                    phase = ArcPhase.AFTERMATH,
                ),
                activeCombat = pveCombat(),
            )
            val host = HostReplica(base)
            val handler = StormglassGameplayCommandHandler(host, seed = 307L)

            handler.handle(
                GameplayWireCommand.CombatAction("routing-arc-action", "p1", CombatActionType.SETUP.name),
                7_000,
            )

            assertEquals(CombatActionType.SETUP, host.state.activeCombat!!.lockedActions.getValue("p1").type)
        }

        test("gameplay handler preserves legacy scenario combat routing") {
            val base = profileWorld("routing-legacy")
            val world = StormglassPersistenceAdapter.encode(
                base,
                ScenarioState(stage = ScenarioStage.MINIBOSS),
                pveCombat(),
            )
            val host = HostReplica(world)
            val handler = StormglassGameplayCommandHandler(host, seed = 308L)

            handler.handle(
                GameplayWireCommand.CombatAction("routing-legacy-action", "p1", CombatActionType.SETUP.name),
                8_000,
            )

            val restored = StormglassPersistenceAdapter.decode(host.state)
            assertEquals(CombatActionType.SETUP, restored.combat!!.lockedActions.getValue("p1").type)
        }

        test("handler retry of resolved hunt combat remains idempotent after binding clears") {
            val quest = hunt("routing-retry-victory")
            val base = boundHuntWorld("routing-retry-world", quest, 309L).let { world ->
                world.copy(activeCombat = world.activeCombat!!.copy(enemy = world.activeCombat.enemy.copy(hp = 1)))
            }
            val host = HostReplica(base)
            val handler = StormglassGameplayCommandHandler(host, seed = 309L)
            handler.handle(
                GameplayWireCommand.CombatAction("routing-retry-p1", "p1", CombatActionType.SETUP.name),
                9_000,
            )
            val command = GameplayWireCommand.CombatAction(
                "routing-retry-p2",
                "p2",
                CombatActionType.FINISHER.name,
            )

            val first = handler.handle(command, 9_001)
            val afterFirst = host.state
            val retry = handler.handle(command, 9_002)

            assertEquals(first.eventId, retry.eventId)
            assertEquals(afterFirst, host.state)
            assertEquals(null, host.state.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG])
            assertEquals(1, host.state.questBoard.active.getValue(quest.questId).progress)
        }
    }

    private fun hubWorld(id: String, quest: QuestDefinition): WorldState = profileWorld(id).copy(
        activeArc = ArcState(
            arcId = "arc-$id",
            islandId = "ironwake-atoll",
            seed = 44L,
            archetype = ArcArchetype.ISLAND_CRISIS,
            phase = ArcPhase.COMPLETE,
        ),
        questBoard = QuestBoardState(
            active = mapOf(
                quest.questId to QuestProgress(quest, QuestStatus.ACTIVE, 0, "p1"),
            ),
        ),
    )

    private fun boundHuntWorld(
        id: String,
        quest: QuestDefinition,
        seed: Long,
        busoshoku: Boolean = false,
    ): WorldState {
        var world = hubWorld(id, quest)
        if (busoshoku) {
            val p1 = world.players.getValue("p1")
            val profile = p1.profile!!.copy(
                haki = p1.profile.haki.copy(
                    disciplines = p1.profile.haki.disciplines +
                        (HakiType.BUSOSHOKU to HakiDiscipline(mastery = 1, useCount = 0)),
                ),
            )
            world = world.copy(players = world.players + ("p1" to p1.copy(profile = profile, energy = 12, maxEnergy = 12)))
        }
        val progress = world.questBoard.active.getValue(quest.questId)
        return world.copy(
            activeCombat = QuestHuntFactory.create(world, progress, seed),
            worldFlags = world.worldFlags + (QuestHuntCoordinator.ACTIVE_QUEST_FLAG to quest.questId),
        )
    }

    private fun profileWorld(id: String): WorldState {
        val p1 = createdProfile("Kairo")
        val p2 = createdProfile("Namiya")
        return WorldState(
            campaignId = id,
            islandId = "ironwake-atoll",
            players = mapOf(
                "p1" to PlayerState("p1", p1.name, p1.maxHp, p1.maxHp, 0, p1.maxEnergy, p1.maxEnergy, p1),
                "p2" to PlayerState("p2", p2.name, p2.maxHp, p2.maxHp, 0, p2.maxEnergy, p2.maxEnergy, p2),
            ),
        )
    }

    private fun createdProfile(name: String) = when (
        val result = CharacterCreation.create(CharacterCreationTest.validDraft().copy(name = name))
    ) {
        is CharacterCreationResult.Success -> result.profile
        is CharacterCreationResult.Invalid -> error(result.errors.joinToString())
    }

    private fun hunt(id: String) = QuestDefinition(
        questId = id,
        islandId = "ironwake-atoll",
        title = "Caçada aos saqueadores",
        type = QuestType.HUNT,
        rarity = QuestRarity.COMMON,
        issuerFaction = "CIVILIANS",
        targetId = "dock-raiders",
        requiredAmount = 3,
    )

    private fun boss(id: String) = QuestDefinition(
        questId = id,
        islandId = "ironwake-atoll",
        title = "Derrubar o executor",
        type = QuestType.BOSS,
        rarity = QuestRarity.COMMON,
        issuerFaction = "CIVILIANS",
        targetId = "island-enforcer",
        requiredAmount = 1,
    )

    private fun pveCombat() = CombatState(
        round = 1,
        players = mapOf(
            "p1" to Combatant("p1", "Kairo", 30, 30),
            "p2" to Combatant("p2", "Namiya", 30, 30),
        ),
        enemy = EnemyCombatant("pve", "PvE", 80, 80, 10),
        telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
        status = CombatStatus.ACTIVE,
    )
}
