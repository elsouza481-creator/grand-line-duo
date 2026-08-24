package grandlineduo.game.quest

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.director.DirectorDifficulty
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object QuestDirectorBridgeTest {
    fun register() {
        test("quest board generation is deterministic for identical stable inputs") {
            val world = baseWorld()

            val first = QuestDirectorBridge.refresh(
                world = world,
                seed = 77L,
                difficulty = DirectorDifficulty.NORMAL,
                presentFactions = setOf("CIVILIANS", "MARINES", "UNDERWORLD"),
            )
            val second = QuestDirectorBridge.refresh(
                world = world,
                seed = 77L,
                difficulty = DirectorDifficulty.NORMAL,
                presentFactions = setOf("UNDERWORLD", "MARINES", "CIVILIANS"),
            )

            assertEquals(first.questBoard, second.questBoard)
            assertEquals(3, first.questBoard.offers.size)
            assertEquals(1L, first.questBoard.generationIndex)
        }

        test("refresh advances generation and produces new stable quest ids") {
            val first = QuestDirectorBridge.refresh(baseWorld(), 77L, DirectorDifficulty.NORMAL, setOf("CIVILIANS"))
            val second = QuestDirectorBridge.refresh(first, 77L, DirectorDifficulty.NORMAL, setOf("CIVILIANS"))

            assertEquals(2L, second.questBoard.generationIndex)
            assertNotEquals(first.questBoard.offers.keys, second.questBoard.offers.keys)
        }

        test("relaxed low bounty board cannot emit epic or legendary contracts") {
            val world = baseWorld().copy(
                players = mapOf(
                    "p1" to PlayerState("p1", "Kairo", 20, 20, 0),
                    "p2" to PlayerState("p2", "Mira", 20, 20, 0),
                ),
            )

            val generated = QuestDirectorBridge.refresh(
                world,
                seed = 11L,
                difficulty = DirectorDifficulty.RELAXED,
                presentFactions = setOf("CIVILIANS", "MARINES"),
            )

            assertTrue(generated.questBoard.offers.values.all { it.rarity <= QuestRarity.RARE })
        }

        test("brutal high bounty board exposes legendary endgame contract") {
            val world = baseWorld().copy(
                players = mapOf(
                    "p1" to PlayerState("p1", "Kairo", 20, 20, 80_000_000L),
                    "p2" to PlayerState("p2", "Mira", 20, 20, 70_000_000L),
                ),
            )

            val generated = QuestDirectorBridge.refresh(
                world,
                seed = 991L,
                difficulty = DirectorDifficulty.BRUTAL,
                presentFactions = setOf("CIVILIANS", "MARINES", "UNDERWORLD", "BOUNTY_HUNTERS"),
            )

            assertTrue(generated.questBoard.offers.values.any { it.rarity == QuestRarity.LEGENDARY })
        }

        test("resolved contract ids are not re-offered for the same generation inputs") {
            val world = baseWorld()
            val generated = QuestDirectorBridge.refresh(
                world,
                seed = 44L,
                difficulty = DirectorDifficulty.NORMAL,
                presentFactions = setOf("CIVILIANS", "MARINES", "UNDERWORLD"),
            )
            val resolvedId = generated.questBoard.offers.keys.sorted().first()
            val replayWorld = world.copy(
                questBoard = QuestBoardState(
                    generationIndex = 0,
                    completedQuestIds = setOf(resolvedId),
                ),
            )

            val replay = QuestDirectorBridge.refresh(
                replayWorld,
                seed = 44L,
                difficulty = DirectorDifficulty.NORMAL,
                presentFactions = setOf("CIVILIANS", "MARINES", "UNDERWORLD"),
            )

            assertTrue(resolvedId !in replay.questBoard.offers)
            assertTrue(resolvedId in replay.questBoard.completedQuestIds)
        }
    }

    private fun baseWorld() = WorldState(
        campaignId = "director-quest-test",
        islandId = "ironwake-atoll",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 20, 20, 4_000_000L),
            "p2" to PlayerState("p2", "Mira", 20, 20, 3_000_000L),
        ),
        worldFlags = mapOf("HAS_LOG_POSE" to "1"),
    )
}
