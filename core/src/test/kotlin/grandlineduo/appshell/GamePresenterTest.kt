package grandlineduo.appshell

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph
import grandlineduo.game.quest.QuestBoardState
import grandlineduo.game.quest.QuestDefinition
import grandlineduo.game.quest.QuestFieldActionType
import grandlineduo.game.quest.QuestFieldAttemptResult
import grandlineduo.game.quest.QuestFieldState
import grandlineduo.game.quest.QuestObjectiveEventType
import grandlineduo.game.quest.QuestProgress
import grandlineduo.game.quest.QuestRarity
import grandlineduo.game.quest.QuestReward
import grandlineduo.game.quest.QuestStatus
import grandlineduo.game.quest.QuestType
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.game.powers.HakiDiscipline
import grandlineduo.game.powers.HakiState
import grandlineduo.game.powers.HakiType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object GamePresenterTest {
    fun register() {
        test("presenter asks local player to create character first") {
            val world = baseWorld()
            assertEquals(GameScreen.CHARACTER_CREATION, GamePresenter.present(world, "p1").screen)
        }

        test("coop host waits until remote character is ready") {
            val root = java.nio.file.Files.createTempDirectory("gld-present-wait")
            GameSessionCoordinator(root).use { session ->
                session.startHost("Host", "present-wait")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Host Hero"))
                val view = GamePresenter.present(session.worldState(), "p1")
                assertEquals(GameScreen.WAITING_FOR_PARTNER, view.screen)
                assertTrue(view.actions.isEmpty())
            }
        }

        test("presenter exposes Stormglass choices after both profiles exist") {
            val world = profiledWorld()
            val presentation = GamePresenter.present(world, "p1")
            assertEquals(GameScreen.STORY, presentation.screen)
            assertTrue(presentation.actions.any { it.id == "help_dockworker" })
        }

        test("hub exposes contracts shop and only authoritative P1 can set sail") {
            val root = java.nio.file.Files.createTempDirectory("gld-present-hub")
            GameSessionCoordinator(root).use { session ->
                session.startSolo("hub-present")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Lio"))
                val complete = session.worldState().copy(worldFlags = session.worldState().worldFlags + ("sg.stage" to "COMPLETE"))
                val p1 = GamePresenter.present(complete, "p1")
                val p2 = GamePresenter.present(complete, "p2")
                assertTrue(p1.actions.any { it.id == "QUESTS" && it.kind == "MENU" })
                assertTrue(p1.actions.any { it.id == "SHOP" })
                assertTrue(p1.actions.any { it.id == "TRAINING" })
                assertTrue(p1.actions.any { it.id == "SAIL" })
                assertTrue(p2.actions.none { it.id == "SAIL" })
            }
        }

        test("quest board presentation exposes offers progress rewards and lifecycle actions") {
            val offer = quest("offer-1", QuestRarity.RARE, requiredAmount = 3)
            val active = quest("active-1", QuestRarity.EPIC, requiredAmount = 4, type = QuestType.RESCUE)
            val ready = quest("ready-1", QuestRarity.LEGENDARY, requiredAmount = 1)
            val world = profiledWorld().copy(
                questBoard = QuestBoardState(
                    generationIndex = 7,
                    offers = mapOf(offer.questId to offer),
                    active = mapOf(
                        active.questId to QuestProgress(active, QuestStatus.ACTIVE, progress = 2, acceptedBy = "p1"),
                        ready.questId to QuestProgress(ready, QuestStatus.READY_TO_TURN_IN, progress = 1, acceptedBy = "p2"),
                    ),
                ),
            )

            val presentation = GamePresenter.presentQuests(world, "p1")

            assertEquals(GameScreen.QUESTS, presentation.screen)
            assertTrue(presentation.body.contains("RARE"))
            assertTrue(presentation.body.contains("EPIC"))
            assertTrue(presentation.body.contains("2/4"))
            assertTrue(presentation.body.contains("Berries"))
            assertTrue(presentation.actions.any { it.id == "REFRESH" && it.kind == "QUEST" })
            assertTrue(presentation.actions.any { it.id == "ACCEPT|offer-1|1" && it.kind == "QUEST" })
            assertTrue(presentation.actions.any { it.id == "PROGRESS|active-1|1" && it.kind == "QUEST" })
            assertTrue(presentation.actions.any { it.id == "TURN_IN|ready-1|1" && it.kind == "QUEST" })
        }

        test("boss contract exposes start boss instead of manual progress") {
            val boss = quest("boss-1", QuestRarity.EPIC, requiredAmount = 1, type = QuestType.BOSS)
            val readyBoss = quest("boss-ready", QuestRarity.LEGENDARY, requiredAmount = 1, type = QuestType.BOSS)
            val world = profiledWorld().copy(
                questBoard = QuestBoardState(
                    active = mapOf(
                        boss.questId to QuestProgress(boss, QuestStatus.ACTIVE, progress = 0, acceptedBy = "p1"),
                        readyBoss.questId to QuestProgress(readyBoss, QuestStatus.READY_TO_TURN_IN, progress = 1, acceptedBy = "p2"),
                    ),
                ),
            )

            val presentation = GamePresenter.presentQuests(world, "p1")

            assertTrue(presentation.actions.any {
                it.id == "START_BOSS|boss-1|1" &&
                    it.kind == "QUEST" &&
                    it.label == "Enfrentar alvo • Contrato boss-1"
            })
            assertTrue(presentation.actions.none { it.id == "PROGRESS|boss-1|1" })
            assertTrue(presentation.actions.any { it.id == "TURN_IN|boss-ready|1" })
        }

        test("hunt contract exposes tracking continuing and turn in without manual progress") {
            val fresh = quest("hunt-fresh", QuestRarity.COMMON, requiredAmount = 3)
            val continued = quest("hunt-continued", QuestRarity.RARE, requiredAmount = 6)
            val ready = quest("hunt-ready", QuestRarity.EPIC, requiredAmount = 9)
            val world = profiledWorld().copy(
                questBoard = QuestBoardState(
                    active = mapOf(
                        fresh.questId to QuestProgress(fresh, QuestStatus.ACTIVE, progress = 0, acceptedBy = "p1"),
                        continued.questId to QuestProgress(continued, QuestStatus.ACTIVE, progress = 2, acceptedBy = "p2"),
                        ready.questId to QuestProgress(ready, QuestStatus.READY_TO_TURN_IN, progress = 9, acceptedBy = "p1"),
                    ),
                ),
            )

            val presentation = GamePresenter.presentQuests(world, "p1")

            assertTrue(presentation.actions.any {
                it.id == "START_HUNT|hunt-fresh|1" &&
                    it.kind == "QUEST" &&
                    it.label == "Rastrear e enfrentar alvo • Contrato hunt-fresh"
            })
            assertTrue(presentation.actions.any {
                it.id == "START_HUNT|hunt-continued|1" &&
                    it.kind == "QUEST" &&
                    it.label == "Continuar caçada • Contrato hunt-continued"
            })
            assertTrue(presentation.actions.none { it.id.startsWith("PROGRESS|hunt-") })
            assertTrue(presentation.actions.any { it.id == "TURN_IN|hunt-ready|1" })
        }

        test("explore and collect expose field actions without manual progress") {
            val exploreFresh = quest("explore-fresh", QuestRarity.COMMON, 3, QuestType.EXPLORE)
            val explorePartial = quest("explore-partial", QuestRarity.COMMON, 3, QuestType.EXPLORE)
            val collectFresh = quest("collect-fresh", QuestRarity.COMMON, 4, QuestType.COLLECT)
            val collectPartial = quest("collect-partial", QuestRarity.COMMON, 4, QuestType.COLLECT)
            val readyExplore = quest("explore-ready", QuestRarity.COMMON, 3, QuestType.EXPLORE)
            val world = profiledWorld().copy(
                questBoard = QuestBoardState(active = mapOf(
                    exploreFresh.questId to QuestProgress(exploreFresh, QuestStatus.ACTIVE, 0, "p1"),
                    explorePartial.questId to QuestProgress(explorePartial, QuestStatus.ACTIVE, 1, "p2"),
                    collectFresh.questId to QuestProgress(collectFresh, QuestStatus.ACTIVE, 0, "p1"),
                    collectPartial.questId to QuestProgress(collectPartial, QuestStatus.ACTIVE, 1, "p2"),
                    readyExplore.questId to QuestProgress(readyExplore, QuestStatus.READY_TO_TURN_IN, 3, "p1"),
                )),
            )

            val view = GamePresenter.presentQuests(world, "p1")

            assertTrue(view.actions.any {
                it.id == "EXPLORE_SITE|explore-fresh|1" &&
                    it.label == "Explorar ruínas • Contrato explore-fresh"
            })
            assertTrue(view.actions.any {
                it.id == "EXPLORE_SITE|explore-partial|1" &&
                    it.label == "Continuar exploração • Contrato explore-partial"
            })
            assertTrue(view.actions.any {
                it.id == "SEARCH_SUPPLIES|collect-fresh|1" &&
                    it.label == "Buscar suprimentos • Contrato collect-fresh"
            })
            assertTrue(view.actions.any {
                it.id == "SEARCH_SUPPLIES|collect-partial|1" &&
                    it.label == "Continuar busca de suprimentos • Contrato collect-partial"
            })
            assertTrue(view.actions.none {
                it.id.startsWith("PROGRESS|explore-") || it.id.startsWith("PROGRESS|collect-")
            })
            assertTrue(view.actions.any { it.id == "TURN_IN|explore-ready|1" })
            assertTrue(view.actions.none { it.id == "EXPLORE_SITE|explore-ready|1" })
        }

        test("zero energy hides field action and explains one pe requirement") {
            val explore = quest("explore-zero", QuestRarity.COMMON, 3, QuestType.EXPLORE)
            val base = profiledWorld()
            val p1 = base.players.getValue("p1")
            val world = base.copy(
                players = base.players + ("p1" to p1.copy(energy = 0)),
                questBoard = QuestBoardState(active = mapOf(
                    explore.questId to QuestProgress(explore, QuestStatus.ACTIVE, 0, "p1"),
                )),
            )

            val view = GamePresenter.presentQuests(world, "p1")

            assertTrue(view.actions.none { it.id.startsWith("EXPLORE_SITE|") })
            assertTrue(view.body.contains("Requer 1 PE para uma nova tentativa."))
        }

        test("field contract renders authoritative last d20 with signed modifiers") {
            val collect = quest("collect-roll", QuestRarity.EPIC, 12, QuestType.COLLECT)
            var positive = profiledWorld().copy(
                questBoard = QuestBoardState(active = mapOf(
                    collect.questId to QuestProgress(collect, QuestStatus.ACTIVE, 3, "p2"),
                )),
            )
            positive = QuestFieldState.writeAttemptResult(
                positive,
                collect.questId,
                fieldResult(collect, modifier = 3, total = 17, success = true),
            )
            val positiveView = GamePresenter.presentQuests(positive, "p1")
            assertTrue(positiveView.body.contains(
                "Último teste: P2 • INT + MEDICINE • d20 14 + 3 = 17 vs CD 15 • SUCESSO • -1 PE"
            ))

            val explore = quest("explore-negative", QuestRarity.EPIC, 9, QuestType.EXPLORE)
            var negative = profiledWorld().copy(
                questBoard = QuestBoardState(active = mapOf(
                    explore.questId to QuestProgress(explore, QuestStatus.ACTIVE, 1, "p1"),
                )),
            )
            negative = QuestFieldState.writeAttemptResult(
                negative,
                explore.questId,
                fieldResult(
                    explore,
                    action = QuestFieldActionType.EXPLORE_SITE,
                    objective = QuestObjectiveEventType.LOCATION_VISITED,
                    checkId = "PER + SURVIVAL",
                    modifier = -1,
                    total = 13,
                    success = false,
                ),
            )
            val negativeView = GamePresenter.presentQuests(negative, "p1")
            assertTrue(negativeView.body.contains("d20 14 - 1 = 13"))
            assertTrue(negativeView.body.contains("FALHA • -1 PE"))
            assertTrue(!negativeView.body.contains("+ -1"))
        }

        test("remaining migration quest types retain manual progress") {
            val active = listOf(
                QuestType.RESCUE,
                QuestType.ESCORT,
                QuestType.INVESTIGATE,
            ).associate { type ->
                val q = quest("migration-${type.name.lowercase()}", QuestRarity.COMMON, 2, type)
                q.questId to QuestProgress(q, QuestStatus.ACTIVE, 0, "p1")
            }
            val world = profiledWorld().copy(questBoard = QuestBoardState(active = active))

            val presentation = GamePresenter.presentQuests(world, "p1")

            active.keys.forEach { questId ->
                assertTrue(presentation.actions.any { it.id == "PROGRESS|$questId|1" })
            }
        }

        test("presenter exposes tactical actions while combat is active") {
            val world = StormglassPersistenceAdapter.encode(
                profiledWorld(), ScenarioState(stage = grandlineduo.game.scenario.ScenarioStage.MINIBOSS), combat()
            )
            val p = GamePresenter.present(world, "p1")
            assertEquals(GameScreen.COMBAT, p.screen)
            assertTrue(p.actions.any { it.id == CombatActionType.DODGE.name })
        }

        test("presenter exposes awakened powers separately from basic combat actions") {
            val base = profiledWorld()
            val p1 = base.players.getValue("p1")
            val powered = p1.profile!!.copy(
                haki = HakiState(disciplines = mapOf(HakiType.BUSOSHOKU to HakiDiscipline(1)))
            )
            val world = StormglassPersistenceAdapter.encode(
                base.copy(players = base.players + ("p1" to p1.copy(profile = powered, energy = 10, maxEnergy = 10))),
                ScenarioState(stage = grandlineduo.game.scenario.ScenarioStage.MINIBOSS),
                combat(),
            )
            val presentation = GamePresenter.present(world, "p1")
            assertTrue(presentation.actions.any { it.id == "HAKI_BUSOSHOKU" && it.kind == "POWER" })
            assertTrue(presentation.actions.none { it.id == "HAKI_BUSOSHOKU" && it.kind == "COMBAT" })
        }
    }

    private fun fieldResult(
        quest: QuestDefinition,
        action: QuestFieldActionType = QuestFieldActionType.SEARCH_SUPPLIES,
        objective: QuestObjectiveEventType = QuestObjectiveEventType.ITEM_ACQUIRED,
        checkId: String = "INT + MEDICINE",
        modifier: Int,
        total: Int,
        success: Boolean,
    ) = QuestFieldAttemptResult(
        actionType = action,
        questId = quest.questId,
        targetId = quest.targetId,
        islandId = quest.islandId,
        actorId = "p2",
        attemptOrdinal = 1,
        checkId = checkId,
        roll = 14,
        modifier = modifier,
        total = total,
        difficultyClass = 15,
        success = success,
        objectiveEventType = objective,
        progressAmount = 1,
    )

    private fun quest(
        id: String,
        rarity: QuestRarity,
        requiredAmount: Int,
        type: QuestType = QuestType.HUNT,
    ) = QuestDefinition(
        questId = id,
        islandId = "stormglass-cay",
        title = "Contrato $id",
        type = type,
        rarity = rarity,
        issuerFaction = "LOCALS",
        targetId = when (type) {
            QuestType.EXPLORE -> "forgotten-ruins"
            QuestType.COLLECT -> "medical-supplies"
            else -> "corsair"
        },
        requiredAmount = requiredAmount,
        reward = QuestReward(berries = 1_500, evolutionPoints = 2),
    )

    private fun baseWorld() = WorldState(
        campaignId = "present",
        players = mapOf(
            "p1" to PlayerState("p1", "P1", 20, 20, 0),
            "p2" to PlayerState("p2", "P2", 20, 20, 0),
        ),
    )

    private fun profiledWorld(): WorldState {
        val draft1 = GameSessionCoordinatorTest.validDraft("Arlen")
        val draft2 = GameSessionCoordinatorTest.validDraft("Mako")
        val p1 = (CharacterCreation.create(draft1) as CharacterCreationResult.Success).profile
        val p2 = (CharacterCreation.create(draft2) as CharacterCreationResult.Success).profile
        return baseWorld().copy(players = mapOf(
            "p1" to baseWorld().players.getValue("p1").copy(name=p1.name, hp=p1.maxHp, maxHp=p1.maxHp, energy=p1.maxEnergy, maxEnergy=p1.maxEnergy, profile=p1),
            "p2" to baseWorld().players.getValue("p2").copy(name=p2.name, hp=p2.maxHp, maxHp=p2.maxHp, energy=p2.maxEnergy, maxEnergy=p2.maxEnergy, profile=p2),
        ))
    }

    private fun combat() = CombatState(
        round = 1,
        players = mapOf("p1" to Combatant("p1","Arlen",30,30), "p2" to Combatant("p2","Mako",30,30)),
        enemy = EnemyCombatant("boss","Boss",80,80,12),
        telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE,"p1"),
    )
}
