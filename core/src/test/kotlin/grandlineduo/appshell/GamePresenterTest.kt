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

        test("hub exposes shop and only authoritative P1 can set sail") {
            val root = java.nio.file.Files.createTempDirectory("gld-present-hub")
            GameSessionCoordinator(root).use { session ->
                session.startSolo("hub-present")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Lio"))
                val complete = session.worldState().copy(worldFlags = session.worldState().worldFlags + ("sg.stage" to "COMPLETE"))
                val p1 = GamePresenter.present(complete, "p1")
                val p2 = GamePresenter.present(complete, "p2")
                assertTrue(p1.actions.any { it.id == "SHOP" })
                assertTrue(p1.actions.any { it.id == "TRAINING" })
                assertTrue(p1.actions.any { it.id == "SAIL" })
                assertTrue(p2.actions.none { it.id == "SAIL" })
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
