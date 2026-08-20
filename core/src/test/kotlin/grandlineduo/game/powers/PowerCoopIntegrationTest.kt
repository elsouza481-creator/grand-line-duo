package grandlineduo.game.powers

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.network.*
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.character.CharacterCoopIntegrationTest
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.arc.ArcArchetype
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcState
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object PowerCoopIntegrationTest {
    fun register() {
        test("host-authoritative Haki and Devil Fruit events autosave and survive P2 restart reconnect") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-power-host"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-power-client"))
            val initial = CharacterCoopIntegrationTest.initialWorld("power-coop-1")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 77, snapshotStore = hostStore)
            val powers = PowerProgressionCoordinator(host, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    handler.handle(
                        GameplayWireCommand.CharacterCreate("power-char-p1", "p1", CharacterCreationTest.validDraft()),
                        1000,
                    )
                    client.sendGameplay(
                        GameplayWireCommand.CharacterCreate(
                            "power-char-p2",
                            "p2",
                            CharacterCreationTest.validDraft().copy(name = "Namiya"),
                        )
                    )

                    powers.setLatentHaoshoku("latent-p1", "p1", true, 2000)
                    powers.awakenHaki(
                        "awaken-p1-hao", "p1", HakiType.HAOSHOKU, HakiTrigger.EXTREME_WILL, 5, 2001,
                    )
                    powers.awakenHaki(
                        "awaken-p2-ken", "p2", HakiType.KENBUNSHOKU, HakiTrigger.TRAINING, 2, 2002,
                    )
                    val fruit = DevilFruitEngineTest.sampleFruit()
                    val consumed = powers.consumeDevilFruit("fruit-p2", "p2", fruit, identified = false, 2003)
                    val duplicate = powers.consumeDevilFruit("fruit-p2", "p2", fruit, identified = false, 2004)
                    assertEquals(consumed.eventId, duplicate.eventId)
                    repeat(3) { index -> powers.recordDevilFruitUse("fruit-use-$index", "p2", 2010L + index) }
                    powers.trainDevilFruitMastery("fruit-train-p2", "p2", 2020)
                    client.refresh()

                    val p1 = host.state.players.getValue("p1").profile!!
                    val p2 = host.state.players.getValue("p2").profile!!
                    assertTrue(HakiType.HAOSHOKU in p1.haki.disciplines)
                    assertTrue(HakiType.KENBUNSHOKU in p2.haki.disciplines)
                    assertEquals(1, p2.devilFruit?.mastery)
                    assertEquals(null, p2.devilFruit?.revealedName)
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                    client.disconnect()
                }

                val restartedReplica = ClientReplica(clientStore.loadLatestValid()!!)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", restartedReplica, clientStore).use { client ->
                    client.connect()
                    assertEquals(host.state, restartedReplica.state)
                    assertEquals(
                        CanonicalStateHasher.hash(host.state),
                        CanonicalStateHasher.hash(restartedReplica.state),
                    )
                }
            }
        }

        test("P2 power action crosses real TCP and converges with host") {
            val baseProfile = (CharacterCreation.create(CharacterCreationTest.validDraft().copy(name = "Namiya")) as CharacterCreationResult.Success).profile
            val p2Profile = baseProfile.copy(haki = HakiState(disciplines = mapOf(HakiType.BUSOSHOKU to HakiDiscipline(1))))
            val p1 = PlayerState("p1", "Arlen", 0, 30, 0)
            val p2 = PlayerState("p2", p2Profile.name, p2Profile.maxHp, p2Profile.maxHp, 0, p2Profile.maxEnergy, p2Profile.maxEnergy, p2Profile)
            val arc = ArcState("arc-power-lan", "emberwake", 91L, ArcArchetype.PIRATE_TYRANNY, ArcPhase.AFTERMATH, escalation = 1)
            val combat = CombatState(
                round = 1,
                players = mapOf("p1" to Combatant("p1", p1.name, 0, p1.maxHp), "p2" to Combatant("p2", p2.name, p2.hp, p2.maxHp)),
                enemy = EnemyCombatant("boss", "Boss", 170, 170, 12),
                telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p2"),
            )
            val initial = WorldState(
                campaignId = "power-lan",
                islandId = "emberwake",
                players = mapOf("p1" to p1, "p2" to p2),
                activeArc = arc,
                activeCombat = combat,
            )
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 91)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    client.sendGameplay(GameplayWireCommand.PowerAction("power-lan-1", "p2", "HAKI_BUSOSHOKU"))
                    assertEquals(p2Profile.maxEnergy - 4, host.state.players.getValue("p2").energy)
                    assertTrue((host.state.activeCombat?.enemy?.hp ?: 0) < 170)
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))
                }
            }
        }

        test("character creation applies deterministic secret Haoshoku potential") {
            val candidateSeed = (0L..10000L).first { PowerDiscoveryEngine.hasLatentHaoshoku(it, "p1") }
            val initial = CharacterCoopIntegrationTest.initialWorld("latent-power")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = candidateSeed)
            handler.handle(GameplayWireCommand.CharacterCreate("latent-char", "p1", CharacterCreationTest.validDraft()), 1000)
            assertTrue(host.state.players.getValue("p1").profile!!.haki.latentHaoshoku)
        }

        test("host rejects Haki awakening that violates narrative rules without mutating state") {
            val initial = CharacterCoopIntegrationTest.initialWorld("power-coop-2")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 77)
            handler.handle(
                GameplayWireCommand.CharacterCreate("power-base-p1", "p1", CharacterCreationTest.validDraft()),
                1000,
            )
            val powers = PowerProgressionCoordinator(host)
            val before = host.state
            var rejected = false
            try {
                powers.awakenHaki(
                    "illegal-haoshoku", "p1", HakiType.HAOSHOKU, HakiTrigger.TRAINING, 5, 2000,
                )
            } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(before, host.state)
        }
    }
}
