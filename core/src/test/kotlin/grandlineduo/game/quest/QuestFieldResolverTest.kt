package grandlineduo.game.quest

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.character.Attribute
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.character.Skill
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object QuestFieldResolverTest {
    fun register() {
        test("field rarity maps to exact dc and progress multiplier") {
            assertEquals(10, QuestFieldResolver.difficultyClass(QuestRarity.COMMON))
            assertEquals(12, QuestFieldResolver.difficultyClass(QuestRarity.RARE))
            assertEquals(15, QuestFieldResolver.difficultyClass(QuestRarity.EPIC))
            assertEquals(18, QuestFieldResolver.difficultyClass(QuestRarity.LEGENDARY))
            assertEquals(1, QuestFieldResolver.progressPerSuccess(QuestRarity.COMMON))
            assertEquals(2, QuestFieldResolver.progressPerSuccess(QuestRarity.RARE))
            assertEquals(3, QuestFieldResolver.progressPerSuccess(QuestRarity.EPIC))
            assertEquals(4, QuestFieldResolver.progressPerSuccess(QuestRarity.LEGENDARY))
        }

        test("explore uses perception plus best skill with fixed tie priority") {
            val world = fieldWorld(
                per = 3,
                intelligence = 1,
                skills = mapOf(
                    Skill.PERCEPTION to 2,
                    Skill.SURVIVAL to 2,
                    Skill.INVESTIGATION to 2,
                ),
            )
            val result = QuestFieldResolver.resolve(
                world,
                activeQuest(QuestType.EXPLORE),
                "p1",
                QuestFieldActionType.EXPLORE_SITE,
                1,
                11L,
            )
            assertEquals("PER + PERCEPTION", result.checkId)
            assertEquals(5, result.modifier)
        }

        test("collect chooses numerically best check with fixed tie priority") {
            val world = fieldWorld(
                per = 2,
                intelligence = 3,
                skills = mapOf(
                    Skill.SURVIVAL to 2,
                    Skill.MEDICINE to 1,
                    Skill.INVESTIGATION to 1,
                ),
            )
            val result = QuestFieldResolver.resolve(
                world,
                activeQuest(QuestType.COLLECT),
                "p1",
                QuestFieldActionType.SEARCH_SUPPLIES,
                1,
                12L,
            )
            assertEquals("PER + SURVIVAL", result.checkId)
            assertEquals(4, result.modifier)
        }

        test("identical authoritative inputs yield identical field result") {
            val world = fieldWorld(2, 2, mapOf(Skill.PERCEPTION to 1))
            val progress = activeQuest(QuestType.EXPLORE, QuestRarity.EPIC)
            val a = QuestFieldResolver.resolve(
                world,
                progress,
                "p2",
                QuestFieldActionType.EXPLORE_SITE,
                4,
                99L,
            )
            val b = QuestFieldResolver.resolve(
                world,
                progress,
                "p2",
                QuestFieldActionType.EXPLORE_SITE,
                4,
                99L,
            )
            assertEquals(a, b)
            assertTrue(a.roll in 1..20)
            assertEquals(a.roll + a.modifier, a.total)
            assertEquals(a.total >= a.difficultyClass, a.success)
            assertEquals(QuestObjectiveEventType.LOCATION_VISITED, a.objectiveEventType)
            assertEquals(3, a.progressAmount)
        }

        test("quest target rarity action actor attempt and campaign seed participate in roll seed") {
            val base = activeQuest(QuestType.EXPLORE)
            val seed = QuestFieldResolver.rollSeed(base, "p1", QuestFieldActionType.EXPLORE_SITE, 1, 99L)
            assertTrue(seed != QuestFieldResolver.rollSeed(
                base.copy(definition = base.definition.copy(questId = "other")),
                "p1", QuestFieldActionType.EXPLORE_SITE, 1, 99L,
            ))
            assertTrue(seed != QuestFieldResolver.rollSeed(
                base.copy(definition = base.definition.copy(targetId = "other-target")),
                "p1", QuestFieldActionType.EXPLORE_SITE, 1, 99L,
            ))
            assertTrue(seed != QuestFieldResolver.rollSeed(
                base.copy(definition = base.definition.copy(rarity = QuestRarity.RARE)),
                "p1", QuestFieldActionType.EXPLORE_SITE, 1, 99L,
            ))
            assertTrue(seed != QuestFieldResolver.rollSeed(
                base,
                "p2", QuestFieldActionType.EXPLORE_SITE, 1, 99L,
            ))
            assertTrue(seed != QuestFieldResolver.rollSeed(
                base.copy(definition = base.definition.copy(type = QuestType.COLLECT)),
                "p1", QuestFieldActionType.SEARCH_SUPPLIES, 1, 99L,
            ))
            assertTrue(seed != QuestFieldResolver.rollSeed(
                base,
                "p1", QuestFieldActionType.EXPLORE_SITE, 2, 99L,
            ))
            assertTrue(seed != QuestFieldResolver.rollSeed(
                base,
                "p1", QuestFieldActionType.EXPLORE_SITE, 1, 100L,
            ))
        }

        test("field resolver rejects mismatched field action and quest type") {
            val world = fieldWorld(2, 2, emptyMap())
            val explore = activeQuest(QuestType.EXPLORE)
            val collect = activeQuest(QuestType.COLLECT)

            assertTrue(runCatching {
                QuestFieldResolver.resolve(
                    world,
                    explore,
                    "p1",
                    QuestFieldActionType.SEARCH_SUPPLIES,
                    1,
                    7L,
                )
            }.isFailure)
            assertTrue(runCatching {
                QuestFieldResolver.resolve(
                    world,
                    collect,
                    "p1",
                    QuestFieldActionType.EXPLORE_SITE,
                    1,
                    7L,
                )
            }.isFailure)
        }
    }

    private fun fieldWorld(per: Int, intelligence: Int, skills: Map<Skill, Int>): WorldState {
        val created = CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success
        val profile = created.profile.copy(
            attributes = created.profile.attributes + mapOf(
                Attribute.PER to per,
                Attribute.INT to intelligence,
            ),
            skills = skills,
        )
        return WorldState(
            campaignId = "field-resolver",
            islandId = "shells-town",
            players = mapOf(
                "p1" to PlayerState("p1", "P1", 30, 30, 0, 20, 20, profile),
                "p2" to PlayerState("p2", "P2", 30, 30, 0, 20, 20, profile),
            ),
        )
    }

    private fun activeQuest(
        type: QuestType,
        rarity: QuestRarity = QuestRarity.COMMON,
    ): QuestProgress = QuestProgress(
        QuestDefinition(
            questId = "field-${type.name.lowercase()}",
            islandId = "shells-town",
            title = "Field ${type.name}",
            type = type,
            rarity = rarity,
            issuerFaction = "CIVILIANS",
            targetId = if (type == QuestType.EXPLORE) "forgotten-ruins" else "medical-supplies",
            requiredAmount = if (type == QuestType.EXPLORE) 3 * (rarity.ordinal + 1) else 4 * (rarity.ordinal + 1),
        ),
        QuestStatus.ACTIVE,
        0,
        "p1",
    )
}
