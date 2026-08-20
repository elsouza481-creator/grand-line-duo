package grandlineduo.game.arc

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.DurableCommitFaultInjector
import grandlineduo.core.persistence.SimulatedDurableCommitCrash
import grandlineduo.game.combat.*
import grandlineduo.game.InventoryEngine
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object ArcCombatCoordinatorTest {
    fun register() {
        test("second climax choice automatically starts persistent arc boss combat") {
            val host = HostReplica(worldWithClimax("boss-start"))
            val arc = ArcCoordinator(host)
            arc.choose("climax-p1", "p1", "draw_boss", 1_000)
            assertEquals(null, host.state.activeCombat)
            arc.choose("climax-p2", "p2", "exploit_weakness", 1_001)
            assertEquals(ArcPhase.AFTERMATH, host.state.activeArc!!.phase)
            assertTrue(host.state.activeCombat != null)
            assertEquals(CombatStatus.ACTIVE, host.state.activeCombat!!.status)
        }

        test("arc choices are blocked while boss combat is active") {
            val host = HostReplica(worldWithCombat("boss-block"))
            val arc = ArcCoordinator(host)
            var rejected = false
            try { arc.choose("too-early", "p1", "spare_enemy", 2_000) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }

        test("arc combat locks one action and duplicate command is idempotent") {
            val host = HostReplica(worldWithCombat("boss-lock"))
            val combat = ArcCombatCoordinator(host)
            val first = combat.submitAction("lock-p1", "p1", CombatActionType.SETUP, 3_000)
            val retry = combat.submitAction("lock-p1", "p1", CombatActionType.SETUP, 3_001)
            assertEquals(first.eventId, retry.eventId)
            assertEquals(1, host.state.activeCombat!!.lockedActions.size)
        }

        test("coop combo victory clears boss and unlocks aftermath") {
            val initial = worldWithCombat("boss-victory").copy(
                activeCombat = worldWithCombat("boss-victory").activeCombat!!.copy(
                    enemy = worldWithCombat("boss-victory").activeCombat!!.enemy.copy(hp = 35, maxHp = 140)
                )
            )
            val host = HostReplica(initial)
            val combat = ArcCombatCoordinator(host)
            combat.submitAction("combo-p1", "p1", CombatActionType.SETUP, 4_000)
            val result = combat.submitAction("combo-p2", "p2", CombatActionType.FINISHER, 4_001)
            assertEquals(null, host.state.activeCombat)
            assertEquals(ArcPhase.AFTERMATH, host.state.activeArc!!.phase)
            assertTrue(result.payload["meta.coopCombo"] == "true")
            assertTrue(host.state.worldFlags.keys.any { it.startsWith("ARC_BOSS_DEFEATED:") })
        }

        test("arc combat applies equipped weapon bonus from authoritative world") {
            fun run(world: WorldState): Int {
                val host = HostReplica(world)
                val combat = ArcCombatCoordinator(host)
                combat.submitAction("loadout-p1", "p1", CombatActionType.ATTACK, 4_500)
                val event = combat.submitAction("loadout-p2", "p2", CombatActionType.DEFEND, 4_501)
                return event.payload.getValue("meta.enemyDamage").toInt()
            }
            val base = worldWithCombat("boss-loadout")
            var equipped = InventoryEngine.grant(base, "p1", "iron_sabre", 1)
            equipped = InventoryEngine.equip(equipped, "p1", "iron_sabre")
            assertEquals(run(base) + 4, run(equipped))
        }

        test("party defeat remains explicit for Director follow up") {
            val base = worldWithCombat("boss-defeat")
            val doomed = base.activeCombat!!.copy(
                players = mapOf(
                    "p1" to Combatant("p1", "Kairo", 1, 30),
                    "p2" to Combatant("p2", "Namiya", 0, 28),
                ),
                enemy = base.activeCombat.enemy.copy(hp = 200, maxHp = 200, attackPower = 28),
                telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
            )
            val host = HostReplica(base.copy(activeCombat = doomed))
            val combat = ArcCombatCoordinator(host)
            combat.submitAction("last-stand", "p1", CombatActionType.ATTACK, 5_000)
            assertEquals(CombatStatus.DEFEAT, host.state.activeCombat!!.status)
            assertTrue(host.state.worldFlags.keys.any { it.startsWith("ARC_PARTY_DEFEATED:") })
        }

        test("locked boss action survives crash after durable append before snapshot") {
            val dir = Files.createTempDirectory("gld-arc-boss-durable")
            val initial = worldWithCombat("boss-durable")
            var crashOnce = true
            val store = DurableCampaignStore(
                dir,
                DurableCommitFaultInjector { event ->
                    if (event.commandId == "boss-crash" && crashOnce) {
                        crashOnce = false
                        throw SimulatedDurableCommitCrash()
                    }
                },
            )
            store.initialize(initial)
            val host = HostReplica(initial)
            val combat = ArcCombatCoordinator(host, durableStore = store)
            var crashed = false
            try { combat.submitAction("boss-crash", "p1", CombatActionType.SETUP, 6_000) }
            catch (_: SimulatedDurableCommitCrash) { crashed = true }
            assertTrue(crashed)

            val recovered = DurableCampaignStore(dir).recover()
            assertEquals(host.state, recovered.state)
            assertEquals(CombatActionType.SETUP, recovered.state.activeCombat!!.lockedActions.getValue("p1").type)
        }
    }

    private fun worldWithClimax(id: String) = baseWorld(id).copy(
        activeArc = ArcState(
            arcId = "ironwake:marine:$id",
            islandId = "ironwake-atoll",
            seed = 77L,
            archetype = ArcArchetype.MARINE_OCCUPATION,
            phase = ArcPhase.CLIMAX,
            escalation = 3,
        )
    )

    private fun worldWithCombat(id: String): WorldState {
        val world = baseWorld(id)
        val arc = ArcState(
            arcId = "ironwake:marine:$id",
            islandId = "ironwake-atoll",
            seed = 77L,
            archetype = ArcArchetype.MARINE_OCCUPATION,
            phase = ArcPhase.AFTERMATH,
            escalation = 3,
        )
        return world.copy(activeArc = arc, activeCombat = ArcBossFactory.create(world, arc))
    }

    private fun baseWorld(id: String) = WorldState(
        campaignId = id,
        islandId = "ironwake-atoll",
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 30, 30, 9_000_000L),
            "p2" to PlayerState("p2", "Namiya", 28, 28, 8_000_000L),
        ),
    )
}
