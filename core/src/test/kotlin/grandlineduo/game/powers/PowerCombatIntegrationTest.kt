package grandlineduo.game.powers

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.appshell.GameSessionCoordinator
import grandlineduo.game.arc.ArcArchetype
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object PowerCombatIntegrationTest {
    fun register() {
        test("power combat action spends energy records mastery use and damages arc boss") {
            val profile0 = (CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success).profile
            val profile = profile0.copy(haki = HakiState(disciplines = mapOf(HakiType.BUSOSHOKU to HakiDiscipline(2))))
            val p1 = PlayerState("p1", profile.name, profile.maxHp, profile.maxHp, 0, profile.maxEnergy, profile.maxEnergy, profile)
            val p2 = PlayerState("p2", "Mako", 0, 30, 0)
            val arc = ArcState("arc-power", "emberwake", 71L, ArcArchetype.PIRATE_TYRANNY, ArcPhase.AFTERMATH, escalation = 2)
            val combat = CombatState(
                round = 1,
                players = mapOf("p1" to Combatant("p1", p1.name, p1.hp, p1.maxHp), "p2" to Combatant("p2", p2.name, 0, p2.maxHp)),
                enemy = EnemyCombatant("boss", "Boss", 180, 180, 15),
                telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
            )
            val host = HostReplica(WorldState("power-combat", islandId = "emberwake", players = mapOf("p1" to p1, "p2" to p2), activeArc = arc, activeCombat = combat))
            val handler = StormglassGameplayCommandHandler(host, seed = 5)
            val event = handler.handle(GameplayWireCommand.PowerAction("power-1", "p1", "HAKI_BUSOSHOKU"), 100)

            assertEquals(profile.maxEnergy - 4, host.state.players.getValue("p1").energy)
            assertEquals(1, host.state.players.getValue("p1").profile!!.haki.disciplines.getValue(HakiType.BUSOSHOKU).useCount)
            assertTrue((host.state.activeCombat?.enemy?.hp ?: 0) < 180)
            assertEquals("HAKI_BUSOSHOKU", event.payload["meta.powerTechnique"])
            assertTrue(host.state.activeCombat == null || host.state.activeCombat!!.status == CombatStatus.ACTIVE)
        }


        test("single player session dispatches power action through authoritative coordinator") {
            val root = java.nio.file.Files.createTempDirectory("gld-power-session")
            val campaignId = "power-session"
            val profile0 = (CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success).profile
            val profile = profile0.copy(haki = HakiState(disciplines = mapOf(HakiType.BUSOSHOKU to HakiDiscipline(1))))
            val p1 = PlayerState("p1", profile.name, profile.maxHp, profile.maxHp, 0, profile.maxEnergy, profile.maxEnergy, profile)
            val p2 = PlayerState("p2", "Mako", 0, 30, 0)
            val arc = ArcState("arc-session-power", "emberwake", 72L, ArcArchetype.PIRATE_TYRANNY, ArcPhase.AFTERMATH, escalation = 1)
            val combat = CombatState(
                round = 1,
                players = mapOf("p1" to Combatant("p1", p1.name, p1.hp, p1.maxHp), "p2" to Combatant("p2", p2.name, 0, p2.maxHp)),
                enemy = EnemyCombatant("boss", "Boss", 160, 160, 12),
                telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
            )
            val world = WorldState(
                campaignId = campaignId,
                islandId = "emberwake",
                players = mapOf("p1" to p1, "p2" to p2),
                activeArc = arc,
                activeCombat = combat,
                worldFlags = mapOf("campaign.mode" to "SOLO"),
            )
            DurableCampaignStore(root.resolve(campaignId)).initialize(world)

            GameSessionCoordinator(root).use { session ->
                session.resume(campaignId)
                val after = session.submitPowerAction("HAKI_BUSOSHOKU")
                assertEquals(profile.maxEnergy - 4, after.players.getValue("p1").energy)
                assertTrue((after.activeCombat?.enemy?.hp ?: 0) < 160)
            }
        }
    }
}
