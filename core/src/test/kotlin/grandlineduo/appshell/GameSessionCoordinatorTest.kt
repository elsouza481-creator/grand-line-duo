package grandlineduo.appshell

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.game.character.Attribute
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterDraft
import grandlineduo.game.character.Skill
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.quest.QuestBoardState
import grandlineduo.game.quest.QuestDefinition
import grandlineduo.game.quest.QuestFieldState
import grandlineduo.game.quest.QuestProgress
import grandlineduo.game.quest.QuestRarity
import grandlineduo.game.quest.QuestReward
import grandlineduo.game.quest.QuestStatus
import grandlineduo.game.quest.QuestType
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object GameSessionCoordinatorTest {
    fun register() {
        test("single player creates AI companion and advances both-player narrative") {
            val root = Files.createTempDirectory("gld-solo")
            GameSessionCoordinator(root).use { session ->
                session.startSolo(campaignId = "solo-1")
                session.createCharacter(validDraft("Arlen"))
                val world = session.worldState()
                assertEquals("Arlen", world.players.getValue("p1").name)
                assertTrue(world.players.getValue("p2").profile != null)
                assertTrue(world.worldFlags["campaign.mode"] == "SOLO")

                session.submitScenarioChoice("help_dockworker")
                val restored = StormglassPersistenceAdapter.decode(session.worldState())
                assertEquals(ScenarioStage.INVESTIGATION, restored.scenario.stage)
            }
        }

        test("new host coop waits for human P2 instead of creating AI") {
            val root = Files.createTempDirectory("gld-host")
            GameSessionCoordinator(root).use { session ->
                session.startHost("Test Host", campaignId = "coop-1")
                session.createCharacter(validDraft("Mira"))
                assertEquals(null, session.worldState().players.getValue("p2").profile)
                assertEquals(SessionMode.HOST_COOP, session.mode)
                assertTrue(session.boundPort > 0)
            }
        }

        test("session coordinator exposes authoritative world management actions") {
            val root = Files.createTempDirectory("gld-world-action")
            GameSessionCoordinator(root).use { session ->
                session.startSolo(campaignId = "world-action")
                session.createCharacter(validDraft("Mira"))
                val before = session.worldState().partyBerries
                session.submitWorldAction("SHOP_BUY", "bandage", 1)
                assertEquals(before - 250L, session.worldState().partyBerries)
                assertTrue(grandlineduo.game.InventoryEngine.read(session.worldState(), "p1").items.getValue("bandage") >= 3)
            }
        }

        test("session coordinator completes shared migration quest lifecycle through authoritative command path") {
            val root = Files.createTempDirectory("gld-session-quest")
            GameSessionCoordinator(root).use { session ->
                session.startSolo(campaignId = "session-quest")
                session.createCharacter(validDraft("Mira"))

                session.submitQuestAction("REFRESH")
                val offered = session.worldState().questBoard.offers.values.first {
                    it.type in setOf(QuestType.RESCUE, QuestType.ESCORT, QuestType.INVESTIGATE)
                }
                val berriesBefore = session.worldState().partyBerries

                session.submitQuestAction("ACCEPT", offered.questId)
                assertEquals(QuestStatus.ACTIVE, session.worldState().questBoard.active.getValue(offered.questId).status)

                session.submitQuestAction("PROGRESS", offered.questId, offered.requiredAmount)
                assertEquals(QuestStatus.READY_TO_TURN_IN, session.worldState().questBoard.active.getValue(offered.questId).status)

                session.submitQuestAction("TURN_IN", offered.questId)
                assertTrue(offered.questId in session.worldState().questBoard.completedQuestIds)
                assertEquals(berriesBefore + offered.reward.berries, session.worldState().partyBerries)
            }
        }

        test("solo quest boss enters combat and existing companion planner resolves p2 action") {
            val root = Files.createTempDirectory("gld-session-quest-boss")
            val campaignId = "session-quest-boss"
            val boss = QuestDefinition(
                questId = "solo-boss-1",
                islandId = "stormglass-cay",
                title = "Executor do cais",
                type = QuestType.BOSS,
                rarity = QuestRarity.COMMON,
                issuerFaction = "LOCALS",
                targetId = "dock-enforcer",
                requiredAmount = 1,
                reward = QuestReward(berries = 2_000),
            )
            val p1Profile = (CharacterCreation.create(validDraft("Arlen")) as CharacterCreationResult.Success).profile
            val p2Profile = (CharacterCreation.create(validDraft("Mako")) as CharacterCreationResult.Success).profile
            val initial = WorldState(
                campaignId = campaignId,
                islandId = "stormglass-cay",
                players = mapOf(
                    "p1" to PlayerState(
                        "p1", p1Profile.name, p1Profile.maxHp, p1Profile.maxHp, 0,
                        p1Profile.maxEnergy, p1Profile.maxEnergy, p1Profile,
                    ),
                    "p2" to PlayerState(
                        "p2", p2Profile.name, p2Profile.maxHp, p2Profile.maxHp, 0,
                        p2Profile.maxEnergy, p2Profile.maxEnergy, p2Profile,
                    ),
                ),
                questBoard = QuestBoardState(
                    active = mapOf(
                        boss.questId to QuestProgress(
                            definition = boss,
                            status = QuestStatus.ACTIVE,
                            progress = 0,
                            acceptedBy = "p1",
                        )
                    )
                ),
                worldFlags = mapOf(
                    "campaign.mode" to "SOLO",
                    "campaign.chapter" to "0",
                ),
            )
            DurableCampaignStore(root.resolve(campaignId)).initialize(initial)

            GameSessionCoordinator(root).use { session ->
                session.resume(campaignId)
                session.submitQuestAction("START_BOSS", boss.questId)

                assertEquals(GameScreen.COMBAT, GamePresenter.present(session.worldState(), "p1").screen)
                val before = session.worldState().activeCombat!!

                session.submitCombatAction(CombatActionType.SETUP)

                val after = session.worldState().activeCombat
                assertTrue(after == null || after.round > before.round || "p2" in after.lockedActions)
            }
        }

        test("solo hunt uses existing companion planner without healing or early reward") {
            val root = Files.createTempDirectory("gld-session-quest-hunt")
            val campaignId = "session-quest-hunt"
            val hunt = QuestDefinition(
                questId = "solo-hunt-1",
                islandId = "stormglass-cay",
                title = "Caçar saqueadores do cais",
                type = QuestType.HUNT,
                rarity = QuestRarity.COMMON,
                issuerFaction = "LOCALS",
                targetId = "dock-raiders",
                requiredAmount = 3,
                reward = QuestReward(berries = 2_500),
            )
            val p1Profile = (CharacterCreation.create(validDraft("Arlen")) as CharacterCreationResult.Success).profile
            val p2Profile = (CharacterCreation.create(validDraft("Mako")) as CharacterCreationResult.Success).profile
            val p1Hp = p1Profile.maxHp - 5
            val p2Hp = p2Profile.maxHp - 4
            val p1Energy = p1Profile.maxEnergy - 3
            val base = WorldState(
                campaignId = campaignId,
                islandId = "stormglass-cay",
                partyBerries = 1_234L,
                players = mapOf(
                    "p1" to PlayerState(
                        "p1", p1Profile.name, p1Hp, p1Profile.maxHp, 0,
                        p1Energy, p1Profile.maxEnergy, p1Profile,
                    ),
                    "p2" to PlayerState(
                        "p2", p2Profile.name, p2Hp, p2Profile.maxHp, 0,
                        p2Profile.maxEnergy, p2Profile.maxEnergy, p2Profile,
                    ),
                ),
                questBoard = QuestBoardState(
                    active = mapOf(
                        hunt.questId to QuestProgress(
                            definition = hunt,
                            status = QuestStatus.ACTIVE,
                            progress = 0,
                            acceptedBy = "p1",
                        )
                    )
                ),
                worldFlags = mapOf(
                    "campaign.mode" to "SOLO",
                    "campaign.chapter" to "0",
                    "reward.stormglass" to "true",
                ),
            )
            val scenario = StormglassPersistenceAdapter.decode(base).scenario.copy(
                stage = ScenarioStage.COMPLETE,
                actedThisStage = emptySet(),
            )
            val initial = StormglassPersistenceAdapter.encode(base, scenario, null)
            DurableCampaignStore(root.resolve(campaignId)).initialize(initial)

            GameSessionCoordinator(root).use { session ->
                session.resume(campaignId)
                val berriesBefore = session.worldState().partyBerries

                session.submitQuestAction("START_HUNT", hunt.questId)

                val started = session.worldState()
                assertEquals(GameScreen.COMBAT, GamePresenter.present(started, "p1").screen)
                assertEquals(p1Hp, started.players.getValue("p1").hp)
                assertEquals(p2Hp, started.players.getValue("p2").hp)
                assertEquals(p1Energy, started.players.getValue("p1").energy)
                assertEquals(berriesBefore, started.partyBerries)
                assertEquals(0, started.questBoard.active.getValue(hunt.questId).progress)
                val before = started.activeCombat!!

                session.submitCombatAction(CombatActionType.SETUP)

                val afterWorld = session.worldState()
                val after = afterWorld.activeCombat
                assertTrue(
                    after == null || after.round > before.round ||
                        ("p2" in after.lockedActions && "p1" !in after.lockedActions),
                    "SOLO planner must answer the structured HUNT combat instead of leaving only P1 locked",
                )
                assertEquals(berriesBefore, afterWorld.partyBerries)
            }
        }

        test("solo field quest spends only p1 energy and does not autoplay companion") {
            val root = Files.createTempDirectory("gld-session-quest-field")
            val campaignId = "session-quest-field"
            val explore = QuestDefinition(
                questId = "solo-explore-1",
                islandId = "stormglass-cay",
                title = "Cartografar ruínas esquecidas",
                type = QuestType.EXPLORE,
                rarity = QuestRarity.COMMON,
                issuerFaction = "LOCALS",
                targetId = "forgotten-ruins",
                requiredAmount = 3,
                reward = QuestReward(berries = 2_500),
            )
            val p1Profile = (CharacterCreation.create(validDraft("Arlen")) as CharacterCreationResult.Success).profile
            val p2Profile = (CharacterCreation.create(validDraft("Mako")) as CharacterCreationResult.Success).profile
            val base = WorldState(
                campaignId = campaignId,
                islandId = "stormglass-cay",
                partyBerries = 1_234L,
                players = mapOf(
                    "p1" to PlayerState("p1", p1Profile.name, p1Profile.maxHp, p1Profile.maxHp, 0, 8, 8, p1Profile),
                    "p2" to PlayerState("p2", p2Profile.name, p2Profile.maxHp, p2Profile.maxHp, 0, 9, 9, p2Profile),
                ),
                questBoard = QuestBoardState(active = mapOf(
                    explore.questId to QuestProgress(explore, QuestStatus.ACTIVE, 0, "p1"),
                )),
                worldFlags = mapOf(
                    "campaign.mode" to "SOLO",
                    "campaign.chapter" to "0",
                    "reward.stormglass" to "true",
                ),
            )
            val scenario = StormglassPersistenceAdapter.decode(base).scenario.copy(stage = ScenarioStage.COMPLETE)
            val initial = StormglassPersistenceAdapter.encode(base, scenario, null)
            DurableCampaignStore(root.resolve(campaignId)).initialize(initial)

            GameSessionCoordinator(root).use { session ->
                session.resume(campaignId)
                val berriesBefore = session.worldState().partyBerries

                session.submitQuestAction("EXPLORE_SITE", explore.questId)

                val after = session.worldState()
                assertEquals(7, after.players.getValue("p1").energy)
                assertEquals(9, after.players.getValue("p2").energy)
                assertEquals(1, QuestFieldState.attemptCount(after, explore.questId))
                assertEquals(berriesBefore, after.partyBerries)
                assertTrue(QuestFieldState.readLast(after, explore.questId) != null)
            }

            GameSessionCoordinator(root).use { resumed ->
                resumed.resume(campaignId)
                assertEquals(1, QuestFieldState.attemptCount(resumed.worldState(), explore.questId))
                assertEquals(7, resumed.worldState().players.getValue("p1").energy)
                assertEquals(9, resumed.worldState().players.getValue("p2").energy)
            }
        }

        test("single player save can be resumed with identical state") {
            val root = Files.createTempDirectory("gld-resume")
            val expected = GameSessionCoordinator(root).use { first ->
                first.startSolo(campaignId = "resume-1")
                first.createCharacter(validDraft("Kael"))
                first.submitScenarioChoice("visit_tavern")
                first.worldState()
            }
            GameSessionCoordinator(root).use { second ->
                second.resume("resume-1")
                assertEquals(expected, second.worldState())
                assertEquals(SessionMode.SOLO, second.mode)
            }
        }
    }

    fun validDraft(name: String): CharacterDraft = CharacterDraft(
        name = name,
        age = 22,
        origin = "North Blue",
        appearance = "hair=black;skin=medium;outfit=navy;accessory=none;color=red",
        personality = "Teimoso mas leal",
        dream = "Mapear uma rota impossível",
        fear = "Perder a tripulação",
        profession = "Aventureiro",
        combatStyle = "Espadachim",
        background = "Criado em um porto comercial",
        motivation = "Liberdade",
        pirateRelation = "Desconfiado",
        marineRelation = "Cauteloso",
        importantPerson = "Mentor do porto",
        defect = "Impulsivo",
        attributes = mapOf(
            Attribute.FOR to 2, Attribute.DES to 2, Attribute.CON to 2,
            Attribute.INT to 1, Attribute.PER to 1, Attribute.CAR to 1, Attribute.VON to 1,
        ),
        skills = mapOf(
            Skill.BLADED_WEAPONS to 2, Skill.ATHLETICS to 1, Skill.ACROBATICS to 1,
            Skill.PERCEPTION to 1, Skill.NAVIGATION to 1, Skill.SURVIVAL to 1, Skill.PERSUASION to 1,
        ),
    )
}
