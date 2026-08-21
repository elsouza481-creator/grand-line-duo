package grandlineduo.appshell

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.ClassMasteryEngine
import grandlineduo.game.character.ClassPath
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.game.powers.HakiDiscipline
import grandlineduo.game.powers.HakiState
import grandlineduo.game.powers.HakiType
import grandlineduo.game.world.ExplorationCombatEngine
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.game.world.ExplorationLootEngine
import grandlineduo.game.world.ExplorationQuestEngine
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

        test("hub exposes a physical map movement and interactions only at their tiles") {
            val root = java.nio.file.Files.createTempDirectory("gld-present-hub")
            GameSessionCoordinator(root).use { session ->
                session.startSolo("hub-present")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Lio"))
                val complete = session.worldState().copy(worldFlags = session.worldState().worldFlags + ("sg.stage" to "COMPLETE"))
                val map = ExplorationEngine.mapFor(complete.campaignId, complete.islandId)

                val spawn = GamePresenter.present(complete, "p1")
                assertEquals(map.spawn, spawn.exploration?.playerPosition)
                assertEquals(4, spawn.actions.count { it.kind == "EXPLORE_MOVE" })
                assertTrue(spawn.actions.any { it.id == "INVENTORY" && it.kind == "MENU" })
                assertTrue(spawn.actions.none { it.id in setOf("SHOP", "TRAINING", "SHIP", "CREW") })
                assertTrue(spawn.actions.none { it.kind == "CAMPAIGN" })

                val marketTile = map.interactions.entries.first { it.value == ExplorationInteraction.MARKET }.key
                val marketWorld = ExplorationEngine.place(complete, "p1", marketTile)
                val market = GamePresenter.present(marketWorld, "p1")
                assertEquals(ExplorationInteraction.MARKET, market.exploration?.interaction)
                assertTrue(market.actions.any { it.id == "SHOP" && it.kind == "MENU" })
                assertTrue(market.actions.none { it.id == "TRAINING" })

                val dockTile = map.interactions.entries.first { it.value == ExplorationInteraction.DOCK }.key
                var dockWorld = ExplorationEngine.place(complete, "p1", dockTile)
                dockWorld = ExplorationEngine.place(dockWorld, "p2", dockTile)
                val p1 = GamePresenter.present(dockWorld, "p1")
                val p2 = GamePresenter.present(dockWorld, "p2")
                val routes = p1.actions.filter { it.kind == "CAMPAIGN" }
                assertEquals(3, routes.size)
                assertEquals(setOf("emberwake", "brineveil", "gearfall"), routes.map { it.id }.toSet())
                assertTrue(routes.all { "perigo" in it.label.lowercase() })
                assertTrue(p2.actions.none { it.kind == "CAMPAIGN" })
            }
        }

        test("hub presents physical quest actions only at the matching quest tiles") {
            var world = profiledWorld().copy(
                worldFlags = profiledWorld().worldFlags + ("sg.stage" to "COMPLETE"),
            )
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val npc = map.npcs.values.single { it.questId?.startsWith("local-cache-") == true }
            val questId = npc.questId!!
            val objective = map.questObjectives.values.single { it.questId == questId }

            world = ExplorationEngine.place(world, "p1", npc.position)
            val offered = GamePresenter.present(world, "p1")
            assertTrue(offered.body.contains(npc.name))
            assertTrue(offered.actions.any { it.kind == "QUEST_ACCEPT" && it.id == questId })
            assertTrue(offered.exploration?.visibleQuestObjectives?.isEmpty() == true)

            world = ExplorationQuestEngine.accept(world, "p1", questId)
            val activeAtNpc = GamePresenter.present(world, "p1")
            assertTrue(activeAtNpc.actions.none { it.kind == "QUEST_ACCEPT" })
            assertEquals(setOf(objective.position), activeAtNpc.exploration?.visibleQuestObjectives)

            world = ExplorationEngine.place(world, "p1", objective.position)
            val atObjective = GamePresenter.present(world, "p1")
            assertTrue(atObjective.actions.any { it.kind == "QUEST_PROGRESS" && it.id == questId })

            world = ExplorationQuestEngine.progress(world, "p1", questId)
            world = ExplorationEngine.place(world, "p1", npc.position)
            val returnView = GamePresenter.present(world, "p1")
            assertTrue(returnView.actions.any { it.kind == "QUEST_TURN_IN" && it.id == questId })
        }

        test("hub shows shared physical loot until one player collects it") {
            var world = profiledWorld().copy(
                worldFlags = profiledWorld().worldFlags + ("sg.stage" to "COMPLETE"),
            )
            val pickup = ExplorationEngine.mapFor(world.campaignId, world.islandId).pickups.values.single()
            world = ExplorationEngine.place(world, "p1", pickup.position)

            val before = GamePresenter.present(world, "p1")
            assertEquals(setOf(pickup.position), before.exploration?.visiblePickups)
            assertTrue(before.body.contains(pickup.label))
            assertTrue(before.actions.any { it.kind == "LOOT_COLLECT" && it.id == pickup.id })

            world = ExplorationLootEngine.collect(world, "p1", pickup.id)
            val afterP1 = GamePresenter.present(world, "p1")
            val afterP2 = GamePresenter.present(world, "p2")
            assertTrue(afterP1.exploration?.visiblePickups?.isEmpty() == true)
            assertTrue(afterP2.exploration?.visiblePickups?.isEmpty() == true)
            assertTrue(afterP1.actions.none { it.kind == "LOOT_COLLECT" })
        }

        test("hub shows all live physical enemies and hides only the defeated encounter") {
            var world = profiledWorld().copy(
                worldFlags = profiledWorld().worldFlags + ("sg.stage" to "COMPLETE"),
            )
            val enemies = ExplorationEngine.mapFor(world.campaignId, world.islandId).enemies.values.sortedBy { it.id }
            val enemy = enemies.first()
            val expectedBefore = enemies.map { it.position }.toSet()
            val expectedAfter = enemies.drop(1).map { it.position }.toSet()

            val beforeP1 = GamePresenter.present(world, "p1")
            val beforeP2 = GamePresenter.present(world, "p2")
            assertEquals(expectedBefore, beforeP1.exploration?.visibleEnemies)
            assertEquals(expectedBefore, beforeP2.exploration?.visibleEnemies)

            world = ExplorationEngine.place(world, "p1", enemy.position)
            world = ExplorationCombatEngine.startIfEncountered(world, "p1")
            val combat = world.activeCombat!!
            world = ExplorationCombatEngine.completeVictory(
                world.copy(
                    activeCombat = combat.copy(
                        enemy = combat.enemy.copy(hp = 0),
                        status = CombatStatus.VICTORY,
                    )
                )
            )

            val afterP1 = GamePresenter.present(world, "p1")
            val afterP2 = GamePresenter.present(world, "p2")
            assertEquals(expectedAfter, afterP1.exploration?.visibleEnemies)
            assertEquals(expectedAfter, afterP2.exploration?.visibleEnemies)
        }

        test("presenter exposes primary class mastery progress in status") {
            val base = profiledWorld()
            val player = base.players.getValue("p1")
            val created = CharacterCreation.create(
                GameSessionCoordinatorTest.validDraft("Arlen").copy(classPath = ClassPath.NAVIGATOR)
            ) as CharacterCreationResult.Success
            val profile = created.profile.copy(
                classMastery = ClassMasteryEngine.train(
                    created.profile.classMastery!!,
                    ClassPath.NAVIGATOR,
                    25,
                )
            )
            val world = base.copy(
                players = base.players + (
                    "p1" to player.copy(
                        name = profile.name,
                        hp = profile.maxHp,
                        maxHp = profile.maxHp,
                        energy = profile.maxEnergy,
                        maxEnergy = profile.maxEnergy,
                        profile = profile,
                    )
                )
            )

            val presentation = GamePresenter.present(world, "p1")
            assertTrue(
                presentation.status.any { "Navegador" in it && "nível 0" in it && "25/100 XP" in it },
                "class mastery status must be visible to the player",
            )
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
