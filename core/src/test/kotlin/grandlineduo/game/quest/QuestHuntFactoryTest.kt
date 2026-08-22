package grandlineduo.game.quest

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object QuestHuntFactoryTest {
    fun register() {
        test("hunt factory is deterministic for identical authoritative inputs") {
            val world = world()
            val progress = huntProgress("hunt-common", QuestRarity.COMMON)

            assertEquals(
                QuestHuntFactory.create(world, progress, 44L),
                QuestHuntFactory.create(world, progress, 44L),
            )
        }

        test("hunt rarity maps to exact hp attack and progress tiers") {
            val expected = mapOf(
                QuestRarity.COMMON to Triple(48, 9, 1),
                QuestRarity.RARE to Triple(72, 12, 2),
                QuestRarity.EPIC to Triple(100, 15, 3),
                QuestRarity.LEGENDARY to Triple(135, 18, 4),
            )

            expected.forEach { (rarity, values) ->
                val progress = huntProgress("hunt-${rarity.name.lowercase()}", rarity)
                val state = QuestHuntFactory.create(world(), progress, 77L)
                assertEquals(values.first, state.enemy.maxHp)
                assertEquals(values.second, state.enemy.attackPower)
                assertEquals(values.third, QuestHuntFactory.progressPerVictory(rarity))
            }
        }

        test("hunt carries current player health and maxima without healing") {
            val wounded = world().copy(
                players = mapOf(
                    "p1" to PlayerState("p1", "Kairo", 9, 30, 1L),
                    "p2" to PlayerState("p2", "Namiya", 4, 28, 1L),
                ),
            )

            val state = QuestHuntFactory.create(
                wounded,
                huntProgress("hunt-wounded", QuestRarity.RARE),
                12L,
            )

            assertEquals(9, state.players.getValue("p1").hp)
            assertEquals(30, state.players.getValue("p1").maxHp)
            assertEquals(4, state.players.getValue("p2").hp)
            assertEquals(28, state.players.getValue("p2").maxHp)
        }

        test("hunt encounter index follows rarity scaled objective progress") {
            assertEquals(1, QuestHuntFactory.encounterIndex(huntProgress("c1", QuestRarity.COMMON, progress = 0)))
            assertEquals(2, QuestHuntFactory.encounterIndex(huntProgress("c2", QuestRarity.COMMON, progress = 1)))
            assertEquals(3, QuestHuntFactory.encounterIndex(huntProgress("c3", QuestRarity.COMMON, progress = 2)))
            assertEquals(1, QuestHuntFactory.encounterIndex(huntProgress("r1", QuestRarity.RARE, progress = 0)))
            assertEquals(2, QuestHuntFactory.encounterIndex(huntProgress("r2", QuestRarity.RARE, progress = 2)))
            assertEquals(3, QuestHuntFactory.encounterIndex(huntProgress("r3", QuestRarity.RARE, progress = 4)))
        }

        test("hunt uses target and encounter ordinal as stable enemy identity") {
            val progress = huntProgress("hunt-target", QuestRarity.EPIC, progress = 3)
                .copy(definition = huntProgress("hunt-target", QuestRarity.EPIC).definition.copy(targetId = "dock-raiders"))

            val state = QuestHuntFactory.create(world(), progress, 91L)

            assertEquals("dock-raiders-hunt-2", state.enemy.id)
        }

        test("hunt combat seed changes when encounter ordinal changes") {
            val first = huntProgress("hunt-seed", QuestRarity.COMMON, progress = 0)
            val second = first.copy(progress = 1)

            assertNotEquals(
                QuestHuntFactory.combatSeed(first, 1234L),
                QuestHuntFactory.combatSeed(second, 1234L),
            )
        }

        test("hunt factory rejects non hunt inactive and wrong island progress") {
            val base = huntProgress("hunt-invalid", QuestRarity.COMMON)
            val nonHunt = base.copy(definition = base.definition.copy(type = QuestType.COLLECT))
            val inactive = base.copy(status = QuestStatus.READY_TO_TURN_IN, progress = 3)
            val wrongIsland = base.copy(definition = base.definition.copy(islandId = "other-island"))

            assertTrue(runCatching { QuestHuntFactory.create(world(), nonHunt, 1L) }.isFailure)
            assertTrue(runCatching { QuestHuntFactory.create(world(), inactive, 1L) }.isFailure)
            assertTrue(runCatching { QuestHuntFactory.create(world(), wrongIsland, 1L) }.isFailure)
        }
    }

    private fun world() = WorldState(
        campaignId = "quest-hunt-factory",
        islandId = "ironwake-atoll",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 30, 30, 9_000_000L),
            "p2" to PlayerState("p2", "Namiya", 28, 28, 8_000_000L),
        ),
    )

    private fun huntProgress(
        id: String,
        rarity: QuestRarity,
        progress: Int = 0,
    ): QuestProgress {
        val amount = when (rarity) {
            QuestRarity.COMMON -> 3
            QuestRarity.RARE -> 6
            QuestRarity.EPIC -> 9
            QuestRarity.LEGENDARY -> 12
        }
        return QuestProgress(
            definition = QuestDefinition(
                questId = id,
                islandId = "ironwake-atoll",
                title = "Caçada aos saqueadores",
                type = QuestType.HUNT,
                rarity = rarity,
                issuerFaction = "CIVILIANS",
                targetId = "dock-raiders",
                requiredAmount = amount,
            ),
            status = QuestStatus.ACTIVE,
            progress = progress,
            acceptedBy = "p1",
        )
    }
}
