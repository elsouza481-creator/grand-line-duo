package grandlineduo.game.quest

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.game.InventoryEngine
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyTelegraph
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object QuestBossCoordinatorTest {
    fun register() {
        test("accepted boss contract starts authoritative combat and binding") {
            val quest = bossQuest("quest-boss-start")
            val host = HostReplica(activeWorld("boss-start", quest))
            val coordinator = QuestBossCoordinator(host, campaignSeed = 81L)

            val event = coordinator.start("start-boss", "p2", quest.questId, 1_000)

            assertEquals(quest.questId, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
            assertEquals(CombatStatus.ACTIVE, host.state.activeCombat!!.status)
            assertEquals(quest.targetId, host.state.activeCombat!!.enemy.id)
            assertEquals("STARTED", event.payload["meta.questBoss"])
        }

        test("non boss contract cannot start quest boss combat") {
            val quest = bossQuest("quest-not-boss").copy(type = QuestType.HUNT)
            val initial = activeWorld("not-boss", quest)
            val host = HostReplica(initial)
            val coordinator = QuestBossCoordinator(host, campaignSeed = 82L)

            val result = runCatching { coordinator.start("start-invalid", "p1", quest.questId, 2_000) }

            assertTrue(result.isFailure)
            assertEquals(initial, host.state)
        }

        test("quest boss combat command retry is idempotent") {
            val quest = bossQuest("quest-boss-retry")
            val host = HostReplica(boundWorld("boss-retry", quest, 83L))
            val coordinator = QuestBossCoordinator(host, campaignSeed = 83L)

            val first = coordinator.submitAction("boss-lock", "p1", CombatActionType.SETUP, 3_000)
            val retry = coordinator.submitAction("boss-lock", "p1", CombatActionType.SETUP, 3_001)

            assertEquals(first.eventId, retry.eventId)
            assertEquals(1, host.state.activeCombat!!.lockedActions.size)
        }

        test("quest boss victory clears combat and makes contract ready to turn in") {
            val quest = bossQuest("quest-boss-victory", reward = QuestReward(berries = 9_000))
            val base = boundWorld("boss-victory", quest, 84L)
            val weakBoss = base.activeCombat!!.copy(
                enemy = base.activeCombat.enemy.copy(hp = 30, maxHp = 72),
            )
            val initial = base.copy(activeCombat = weakBoss)
            val berriesBefore = initial.partyBerries
            val host = HostReplica(initial)
            val coordinator = QuestBossCoordinator(host, campaignSeed = 84L)

            coordinator.submitAction("victory-p1", "p1", CombatActionType.SETUP, 4_000)
            coordinator.submitAction("victory-p2", "p2", CombatActionType.FINISHER, 4_001)

            assertEquals(null, host.state.activeCombat)
            assertEquals(null, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
            assertEquals(QuestStatus.READY_TO_TURN_IN, host.state.questBoard.active.getValue(quest.questId).status)
            assertEquals(quest.requiredAmount, host.state.questBoard.active.getValue(quest.questId).progress)
            assertTrue(quest.questId !in host.state.questBoard.completedQuestIds)
            assertEquals(berriesBefore, host.state.partyBerries)
        }

        test("quest boss defeat permanently fails contract without reward") {
            val quest = bossQuest("quest-boss-defeat", reward = QuestReward(berries = 12_000))
            val base = boundWorld("boss-defeat", quest, 85L)
            val doomed = base.activeCombat!!.copy(
                players = mapOf(
                    "p1" to Combatant("p1", "Kairo", 1, 30),
                    "p2" to Combatant("p2", "Namiya", 0, 28),
                ),
                enemy = base.activeCombat.enemy.copy(hp = 200, maxHp = 200, attackPower = 28),
                telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
            )
            val initial = base.copy(activeCombat = doomed)
            val berriesBefore = initial.partyBerries
            val host = HostReplica(initial)
            val coordinator = QuestBossCoordinator(host, campaignSeed = 85L)

            val event = coordinator.submitAction("last-stand", "p1", CombatActionType.ATTACK, 5_000)

            assertEquals(CombatStatus.DEFEAT, host.state.activeCombat!!.status)
            assertEquals(null, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
            assertTrue(quest.questId !in host.state.questBoard.active)
            assertTrue(quest.questId in host.state.questBoard.failedQuestIds)
            assertEquals(berriesBefore, host.state.partyBerries)
            assertEquals("BOSS_DEFEAT", event.payload["meta.questFailure"])
        }

        test("quest boss combat uses authoritative equipment modifiers") {
            fun damage(world: WorldState, campaignSeed: Long): Int {
                val host = HostReplica(world)
                val coordinator = QuestBossCoordinator(host, campaignSeed)
                coordinator.submitAction("loadout-p1", "p1", CombatActionType.ATTACK, 6_000)
                val event = coordinator.submitAction("loadout-p2", "p2", CombatActionType.DEFEND, 6_001)
                return event.payload.getValue("meta.enemyDamage").toInt()
            }

            val quest = bossQuest("quest-boss-loadout")
            val base = boundWorld("boss-loadout", quest, 86L)
            var equipped = InventoryEngine.grant(base, "p1", "iron_sabre", 1)
            equipped = InventoryEngine.equip(equipped, "p1", "iron_sabre")

            assertEquals(damage(base, 86L) + 4, damage(equipped, 86L))
        }
    }

    private fun activeWorld(id: String, quest: QuestDefinition): WorldState = baseWorld(id).copy(
        questBoard = QuestBoardState(
            active = mapOf(
                quest.questId to QuestProgress(
                    definition = quest,
                    status = QuestStatus.ACTIVE,
                    progress = 0,
                    acceptedBy = "p1",
                )
            )
        )
    )

    private fun boundWorld(id: String, quest: QuestDefinition, campaignSeed: Long): WorldState {
        val world = activeWorld(id, quest)
        return world.copy(
            activeCombat = QuestBossFactory.create(world, quest, campaignSeed),
            worldFlags = world.worldFlags + (QuestBossCoordinator.ACTIVE_QUEST_FLAG to quest.questId),
        )
    }

    private fun baseWorld(id: String) = WorldState(
        campaignId = id,
        islandId = "ironwake-atoll",
        partyBerries = 2_000,
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 30, 30, 9_000_000L),
            "p2" to PlayerState("p2", "Namiya", 28, 28, 8_000_000L),
        ),
    )

    private fun bossQuest(
        id: String,
        reward: QuestReward = QuestReward(berries = 1_500),
    ) = QuestDefinition(
        questId = id,
        islandId = "ironwake-atoll",
        title = "Derrubar o executor da ilha",
        type = QuestType.BOSS,
        rarity = QuestRarity.COMMON,
        issuerFaction = "CIVILIANS",
        targetId = "island-enforcer",
        requiredAmount = 1,
        reward = reward,
    )
}
