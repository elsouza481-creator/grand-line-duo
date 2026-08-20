package grandlineduo.game.world

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.powers.HakiDiscipline
import grandlineduo.game.powers.HakiState
import grandlineduo.game.powers.HakiType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ExplorationCombatLanIntegrationTest {
    fun register() {
        test("Haki power action works in free roam combat without an active narrative arc") {
            val profile0 = (CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success).profile
            val profile = profile0.copy(
                haki = HakiState(disciplines = mapOf(HakiType.BUSOSHOKU to HakiDiscipline(2)))
            )
            val p1 = PlayerState(
                "p1", profile.name, profile.maxHp, profile.maxHp, 0,
                profile.maxEnergy, profile.maxEnergy, profile,
            )
            val p2 = PlayerState("p2", "Mako", 40, 40, 0)
            var initial = WorldState(
                campaignId = "free-power",
                islandId = "stormglass-cay",
                players = mapOf("p1" to p1, "p2" to p2),
            )
            val enemy = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).enemies.values.sortedBy { it.id }.first()
            initial = ExplorationEngine.place(initial, "p1", enemy.position)
            initial = ExplorationCombatEngine.startIfEncountered(initial, "p1")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 81)

            val event = handler.handle(
                GameplayWireCommand.PowerAction("free-haki", "p1", "HAKI_BUSOSHOKU"),
                1,
            )

            assertEquals(profile.maxEnergy - 4, host.state.players.getValue("p1").energy)
            assertEquals(1, host.state.players.getValue("p1").profile!!.haki.disciplines.getValue(HakiType.BUSOSHOKU).useCount)
            assertTrue(ExplorationCombatEngine.isActive(host.state))
            assertEquals("HAKI_BUSOSHOKU", event.payload["meta.powerTechnique"])
        }

        test("P2 free roam combat action crosses real TCP and converges with host") {
            var initial = WorldState(
                campaignId = "free-tcp",
                islandId = "stormglass-cay",
                partyBerries = 3_000,
                players = mapOf(
                    "p1" to PlayerState("p1", "A", 40, 40, 0),
                    "p2" to PlayerState("p2", "B", 40, 40, 0),
                ),
            )
            val enemy = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).enemies.values.sortedBy { it.id }.first()
            initial = ExplorationEngine.place(initial, "p2", enemy.position)
            initial = ExplorationCombatEngine.startIfEncountered(initial, "p2")
            initial = initial.copy(activeCombat = initial.activeCombat!!.copy(enemy = initial.activeCombat!!.enemy.copy(hp = 1)))
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 82)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    handler.handle(
                        GameplayWireCommand.CombatAction("free-tcp-p1", "p1", CombatActionType.ATTACK.name),
                        10,
                    )
                    client.sendGameplay(
                        GameplayWireCommand.CombatAction("free-tcp-p2", "p2", CombatActionType.ATTACK.name)
                    )

                    assertEquals(host.state, clientReplica.state)
                    assertEquals(null, host.state.activeCombat)
                    assertTrue(ExplorationCombatEngine.isDefeated(host.state, enemy.id))
                }
            }
        }
    }
}
