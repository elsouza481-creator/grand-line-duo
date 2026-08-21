package grandlineduo.game.quest

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object QuestBossFactoryTest {
    fun register() {
        test("quest boss factory is deterministic for identical inputs") {
            val world = world()
            val quest = boss("boss-common", QuestRarity.COMMON)

            assertEquals(
                QuestBossFactory.create(world, quest, 44L),
                QuestBossFactory.create(world, quest, 44L),
            )
        }

        test("quest boss rarity maps to exact hp and attack tiers") {
            val expected = mapOf(
                QuestRarity.COMMON to (72 to 11),
                QuestRarity.RARE to (108 to 14),
                QuestRarity.EPIC to (150 to 18),
                QuestRarity.LEGENDARY to (200 to 22),
            )

            expected.forEach { (rarity, stats) ->
                val state = QuestBossFactory.create(world(), boss("boss-${rarity.name}", rarity), 77L)
                assertEquals(stats.first, state.enemy.maxHp)
                assertEquals(stats.second, state.enemy.attackPower)
            }
        }

        test("quest boss carries current player health without healing") {
            val state = QuestBossFactory.create(
                world().copy(
                    players = mapOf(
                        "p1" to PlayerState("p1", "Kairo", 9, 30, 1L),
                        "p2" to PlayerState("p2", "Namiya", 4, 28, 1L),
                    )
                ),
                boss("boss-wounded", QuestRarity.RARE),
                12L,
            )

            assertEquals(9, state.players.getValue("p1").hp)
            assertEquals(4, state.players.getValue("p2").hp)
        }

        test("quest boss uses target id as stable enemy identity") {
            val quest = boss("boss-target", QuestRarity.EPIC).copy(targetId = "island-enforcer")

            assertEquals("island-enforcer", QuestBossFactory.create(world(), quest, 91L).enemy.id)
        }
    }

    private fun world() = WorldState(
        campaignId = "quest-boss-factory",
        islandId = "ironwake-atoll",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 30, 30, 9_000_000L),
            "p2" to PlayerState("p2", "Namiya", 28, 28, 8_000_000L),
        ),
    )

    private fun boss(id: String, rarity: QuestRarity) = QuestDefinition(
        questId = id,
        islandId = "ironwake-atoll",
        title = "Derrubar o executor da ilha",
        type = QuestType.BOSS,
        rarity = rarity,
        issuerFaction = "CIVILIANS",
        targetId = "island-enforcer",
        requiredAmount = 1,
    )
}
