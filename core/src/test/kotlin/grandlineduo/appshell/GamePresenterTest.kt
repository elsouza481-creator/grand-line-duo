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
            val active = quest("active-1", QuestRarity.EPIC, requiredAmount = 4)
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

    private fun quest(id: String, rarity: QuestRarity, requiredAmount: Int) = QuestDefinition(
        questId = id,
        islandId = "stormglass-cay",
        title = "Contrato $id",
        type = QuestType.HUNT,
        rarity = rarity,
        issuerFaction = "LOCALS",
        targetId = "corsair",
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
            "p1" to baseWorld().players.getValue("p1").copy(name=p1.name, hp=p1.maxHp, maxHp=p1.maxHp, profile=p1),
            "p2" to baseWorld().players.getValue("p2").copy(name=p2.name, hp=p2.maxHp, maxHp=p2.maxHp, profile=p2),
        ))
    }

    private fun combat() = CombatState(
        round = 1,
        players = mapOf("p1" to Combatant("p1","Arlen",30,30), "p2" to Combatant("p2","Mako",30,30)),
        enemy = EnemyCombatant("boss","Boss",80,80,12),
        telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE,"p1"),
    )
}
