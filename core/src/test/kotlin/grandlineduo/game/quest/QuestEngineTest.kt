package grandlineduo.game.quest

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine
import grandlineduo.game.character.CharacterProfile
import grandlineduo.game.social.SocialState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object QuestEngineTest {
    fun register() {
        test("quest lifecycle accepts progresses and turns in exactly once") {
            val quest = sampleQuest(
                reward = QuestReward(
                    berries = 2_500,
                    evolutionPoints = 3,
                    itemId = "bandage",
                    itemAmount = 1,
                    factionId = "REVOLUTIONARIES",
                    factionStandingDelta = 7,
                    worldFlag = "QUEST_DOCK_RAID_COMPLETE",
                ),
            ).copy(type = QuestType.RESCUE, targetId = "captured-sailor")
            var world = worldWithOffer(quest)

            world = QuestEngine.accept(world, quest.questId, "p2")
            assertEquals(QuestStatus.ACTIVE, world.questBoard.active.getValue(quest.questId).status)
            assertEquals("p2", world.questBoard.active.getValue(quest.questId).acceptedBy)
            assertTrue(quest.questId !in world.questBoard.offers)

            world = QuestEngine.progress(world, quest.questId, 2)
            assertEquals(2, world.questBoard.active.getValue(quest.questId).progress)
            assertEquals(QuestStatus.ACTIVE, world.questBoard.active.getValue(quest.questId).status)

            world = QuestEngine.progress(world, quest.questId, 99)
            assertEquals(3, world.questBoard.active.getValue(quest.questId).progress)
            assertEquals(QuestStatus.READY_TO_TURN_IN, world.questBoard.active.getValue(quest.questId).status)

            world = QuestEngine.turnIn(world, quest.questId)
            assertTrue(quest.questId !in world.questBoard.active)
            assertTrue(quest.questId in world.questBoard.completedQuestIds)
            assertEquals(3_500L, world.partyBerries)
            assertEquals(13, world.players.getValue("p1").profile!!.evolutionPoints)
            assertEquals(13, world.players.getValue("p2").profile!!.evolutionPoints)
            assertEquals(1, InventoryEngine.read(world, "p1").items["bandage"])
            assertEquals(1, InventoryEngine.read(world, "p2").items["bandage"])
            assertEquals(7, world.socialState.factionStanding["REVOLUTIONARIES"])
            assertEquals("1", world.worldFlags["QUEST_DOCK_RAID_COMPLETE"])

            val secondTurnIn = runCatching { QuestEngine.turnIn(world, quest.questId) }
            assertTrue(secondTurnIn.isFailure)
            assertEquals(3_500L, world.partyBerries)
        }

        test("quest acceptance rejects unmet eligibility requirements") {
            val quest = sampleQuest(
                requirement = QuestRequirement(
                    minimumFactionStanding = 20,
                    factionId = "MARINES",
                    minimumTotalBounty = 8_000_000L,
                    requiredWorldFlag = "HAS_LOG_POSE",
                    requiredProfessionContains = "navegador",
                ),
            )
            val world = worldWithOffer(quest)

            val result = runCatching { QuestEngine.accept(world, quest.questId, "p1") }

            assertTrue(result.isFailure)
            assertTrue(quest.questId in world.questBoard.offers)
            assertTrue(quest.questId !in world.questBoard.active)
        }

        test("failed quest leaves active list and enters permanent failed history") {
            val quest = sampleQuest()
            var world = worldWithOffer(quest)
            world = QuestEngine.accept(world, quest.questId, "p1")

            world = QuestEngine.fail(world, quest.questId, "target escaped")

            assertTrue(quest.questId !in world.questBoard.active)
            assertTrue(quest.questId in world.questBoard.failedQuestIds)
            assertTrue(runCatching { QuestEngine.accept(world, quest.questId, "p2") }.isFailure)
        }

        test("boss quest rejects manual progress") {
            val quest = sampleQuest().copy(
                questId = "shells-town-boss-001",
                title = "Derrubar o executor",
                type = QuestType.BOSS,
                targetId = "island-enforcer",
                requiredAmount = 1,
            )
            val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")

            val result = runCatching { QuestEngine.progress(accepted, quest.questId, 1) }

            assertTrue(result.isFailure)
            assertEquals(0, accepted.questBoard.active.getValue(quest.questId).progress)
            assertEquals(QuestStatus.ACTIVE, accepted.questBoard.active.getValue(quest.questId).status)
        }

        test("manual quest progress rejects automated types while remaining migration types still work") {
            listOf(QuestType.HUNT, QuestType.BOSS, QuestType.EXPLORE, QuestType.COLLECT).forEach { type ->
                val quest = sampleQuest().copy(
                    questId = "manual-reject-${type.name.lowercase()}",
                    type = type,
                    targetId = "target-${type.name.lowercase()}",
                    requiredAmount = if (type == QuestType.BOSS) 1 else 3,
                )
                val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")
                assertTrue(runCatching { QuestEngine.progress(accepted, quest.questId, 1) }.isFailure)
                assertEquals(0, accepted.questBoard.active.getValue(quest.questId).progress)
            }

            listOf(QuestType.RESCUE, QuestType.ESCORT, QuestType.INVESTIGATE).forEach { type ->
                val quest = sampleQuest().copy(
                    questId = "manual-keep-${type.name.lowercase()}",
                    type = type,
                    targetId = "target-${type.name.lowercase()}",
                )
                val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")
                assertEquals(
                    1,
                    QuestEngine.progress(accepted, quest.questId, 1)
                        .questBoard.active.getValue(quest.questId).progress,
                )
            }
        }

        test("objective progress advances active hunt clamps and rejects ready hunt") {
            val hunt = sampleQuest().copy(requiredAmount = 3)
            val accepted = QuestEngine.accept(worldWithOffer(hunt), hunt.questId, "p1")

            val advanced = QuestEngine.progressObjective(accepted, hunt.questId, 99)

            assertEquals(3, advanced.questBoard.active.getValue(hunt.questId).progress)
            assertEquals(QuestStatus.READY_TO_TURN_IN, advanced.questBoard.active.getValue(hunt.questId).status)
            assertTrue(runCatching { QuestEngine.progressObjective(advanced, hunt.questId, 1) }.isFailure)
        }

        test("objective progress accepts active hunt explore and collect only") {
            listOf(QuestType.HUNT, QuestType.EXPLORE, QuestType.COLLECT).forEach { type ->
                val quest = sampleQuest().copy(
                    questId = "objective-${type.name.lowercase()}",
                    type = type,
                    targetId = "objective-${type.name.lowercase()}",
                    requiredAmount = 3,
                )
                val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")
                val advanced = QuestEngine.progressObjective(accepted, quest.questId, 1)
                assertEquals(1, advanced.questBoard.active.getValue(quest.questId).progress)
            }

            listOf(QuestType.BOSS, QuestType.RESCUE, QuestType.ESCORT, QuestType.INVESTIGATE).forEach { type ->
                val quest = sampleQuest().copy(
                    questId = "objective-reject-${type.name.lowercase()}",
                    type = type,
                    targetId = "objective-${type.name.lowercase()}",
                    requiredAmount = if (type == QuestType.BOSS) 1 else 3,
                )
                val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")
                assertTrue(runCatching { QuestEngine.progressObjective(accepted, quest.questId, 1) }.isFailure)
                assertEquals(0, accepted.questBoard.active.getValue(quest.questId).progress)
            }
        }

        test("turn in clears only exact field state keys for resolved quest") {
            val a = sampleQuest().copy(
                questId = "field-clean-a",
                type = QuestType.EXPLORE,
                targetId = "forgotten-ruins",
                requiredAmount = 3,
            )
            val b = sampleQuest().copy(
                questId = "field-clean-b",
                type = QuestType.COLLECT,
                targetId = "medical-supplies",
                requiredAmount = 4,
            )
            var world = cleanupWorld(
                QuestProgress(a, QuestStatus.READY_TO_TURN_IN, 3, "p1"),
                QuestProgress(b, QuestStatus.ACTIVE, 0, "p2"),
            )
            world = QuestFieldState.writeAttemptResult(world, a.questId, fieldResult(a, QuestFieldActionType.EXPLORE_SITE))
            world = QuestFieldState.writeAttemptResult(world, b.questId, fieldResult(b, QuestFieldActionType.SEARCH_SUPPLIES))
            world = world.copy(worldFlags = world.worldFlags + ("quest.field.last.roll.${a.questId}.shadow" to "keep"))

            val next = QuestEngine.turnIn(world, a.questId)

            assertEquals(0, QuestFieldState.attemptCount(next, a.questId))
            assertEquals(null, QuestFieldState.readLast(next, a.questId))
            assertEquals(1, QuestFieldState.attemptCount(next, b.questId))
            assertTrue(QuestFieldState.readLast(next, b.questId) != null)
            assertEquals("keep", next.worldFlags["quest.field.last.roll.${a.questId}.shadow"])
        }

        test("fail clears only exact field state keys for failed quest") {
            val a = sampleQuest().copy(
                questId = "field-fail-a",
                type = QuestType.EXPLORE,
                targetId = "forgotten-ruins",
                requiredAmount = 3,
            )
            val b = sampleQuest().copy(
                questId = "field-fail-b",
                type = QuestType.COLLECT,
                targetId = "medical-supplies",
                requiredAmount = 4,
            )
            var world = cleanupWorld(
                QuestProgress(a, QuestStatus.ACTIVE, 1, "p1"),
                QuestProgress(b, QuestStatus.ACTIVE, 1, "p2"),
            )
            world = QuestFieldState.writeAttemptResult(world, a.questId, fieldResult(a, QuestFieldActionType.EXPLORE_SITE))
            world = QuestFieldState.writeAttemptResult(world, b.questId, fieldResult(b, QuestFieldActionType.SEARCH_SUPPLIES))

            val next = QuestEngine.fail(world, a.questId, "abandoned")

            assertEquals(0, QuestFieldState.attemptCount(next, a.questId))
            assertEquals(null, QuestFieldState.readLast(next, a.questId))
            assertEquals(1, QuestFieldState.attemptCount(next, b.questId))
            assertTrue(QuestFieldState.readLast(next, b.questId) != null)
        }

        test("resolving hunt with no field state preserves unrelated world flags") {
            val hunt = sampleQuest()
            val world = cleanupWorld(QuestProgress(hunt, QuestStatus.ACTIVE, 0, "p1")).copy(
                worldFlags = mapOf("unrelated.flag" to "keep"),
            )

            val next = QuestEngine.fail(world, hunt.questId, "hunt defeat")

            assertEquals("keep", next.worldFlags["unrelated.flag"])
        }
    }

    private fun fieldResult(quest: QuestDefinition, action: QuestFieldActionType) = QuestFieldAttemptResult(
        actionType = action,
        questId = quest.questId,
        targetId = quest.targetId,
        islandId = quest.islandId,
        actorId = "p1",
        attemptOrdinal = 1,
        checkId = if (action == QuestFieldActionType.EXPLORE_SITE) "PER + PERCEPTION" else "INT + MEDICINE",
        roll = 14,
        modifier = 3,
        total = 17,
        difficultyClass = 10,
        success = true,
        objectiveEventType = if (action == QuestFieldActionType.EXPLORE_SITE) QuestObjectiveEventType.LOCATION_VISITED else QuestObjectiveEventType.ITEM_ACQUIRED,
        progressAmount = 1,
    )

    private fun cleanupWorld(vararg progress: QuestProgress): WorldState = WorldState(
        campaignId = "cleanup",
        islandId = "shells-town",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 20, 20, 0, profile = profile("Navegador")),
            "p2" to PlayerState("p2", "Mira", 20, 20, 0, profile = profile("Espadachim")),
        ),
        questBoard = QuestBoardState(active = progress.associateBy { it.definition.questId }),
    )

    private fun sampleQuest(
        requirement: QuestRequirement = QuestRequirement(),
        reward: QuestReward = QuestReward(berries = 500),
    ) = QuestDefinition(
        questId = "shells-town-hunt-001",
        islandId = "shells-town",
        title = "Caçada no cais",
        type = QuestType.HUNT,
        rarity = QuestRarity.RARE,
        issuerFaction = "CIVILIANS",
        targetId = "dock-raiders",
        requiredAmount = 3,
        requirement = requirement,
        reward = reward,
    )

    private fun worldWithOffer(quest: QuestDefinition): WorldState {
        val p1 = PlayerState("p1", "Kairo", 20, 20, 3_000_000L, profile = profile("Navegador"))
        val p2 = PlayerState("p2", "Mira", 20, 20, 2_000_000L, profile = profile("Espadachim"))
        return WorldState(
            campaignId = "quest-test",
            islandId = "shells-town",
            partyBerries = 1_000,
            socialState = SocialState(factionStanding = mapOf("MARINES" to 10)),
            players = mapOf("p1" to p1, "p2" to p2),
            questBoard = QuestBoardState(offers = mapOf(quest.questId to quest)),
        )
    }

    private fun profile(profession: String) = CharacterProfile(
        name = profession,
        age = 20,
        origin = "East Blue",
        appearance = "Teste",
        personality = "Teste",
        dream = "Grand Line",
        fear = "Falhar",
        profession = profession,
        combatStyle = "Espada e navegação",
        background = "Teste",
        motivation = "Explorar",
        pirateRelation = "Neutra",
        marineRelation = "Neutra",
        importantPerson = "Tripulação",
        defect = "Teimoso",
        attributes = emptyMap(),
        skills = emptyMap(),
        evolutionPoints = 10,
    )
}
