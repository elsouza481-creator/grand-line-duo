package grandlineduo.game.character

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.*
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object CharacterCoopIntegrationTest {
    fun register() {
        test("P1 local and P2 LAN character creation converge and autosave on both peers") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-char-host"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-char-client"))
            val initial = initialWorld("char-coop-1")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 77, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    handler.handle(
                        GameplayWireCommand.CharacterCreate("create-p1", "p1", CharacterCreationTest.validDraft()),
                        1000,
                    )
                    client.sendGameplay(
                        GameplayWireCommand.CharacterCreate(
                            "create-p2",
                            "p2",
                            CharacterCreationTest.validDraft().copy(
                                name = "Namiya",
                                combatStyle = "Clima-Tact improvisado",
                                dream = "Desenhar a rota impossível da Grand Line",
                            ),
                        )
                    )

                    assertEquals(host.state, clientReplica.state)
                    assertEquals("Kairo", host.state.players.getValue("p1").profile?.name)
                    assertEquals("Namiya", host.state.players.getValue("p2").profile?.name)
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                    assertEquals(
                        CanonicalStateHasher.hash(hostStore.loadLatestValid()!!),
                        CanonicalStateHasher.hash(clientStore.loadLatestValid()!!),
                    )
                }
            }
        }

        test("host rejects invalid P2 character without mutating authoritative state") {
            val initial = initialWorld("char-coop-2")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 77)
            val clientReplica = ClientReplica(initial)
            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    var rejected = false
                    try {
                        client.sendGameplay(
                            GameplayWireCommand.CharacterCreate(
                                "bad-p2",
                                "p2",
                                CharacterCreationTest.validDraft().copy(dream = ""),
                            )
                        )
                    } catch (_: LanSessionException) { rejected = true }
                    assertTrue(rejected)
                    assertEquals(0L, host.state.lastEventId)
                    assertEquals(null, host.state.players.getValue("p2").profile)
                }
            }
        }

        test("invalid character command does not kill the LAN session") {
            val initial = initialWorld("char-coop-recover")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 77)
            val clientReplica = ClientReplica(initial)
            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica).use { client ->
                    client.connect()
                    var rejected = false
                    try {
                        client.sendGameplay(
                            GameplayWireCommand.CharacterCreate(
                                "bad-first",
                                "p2",
                                CharacterCreationTest.validDraft().copy(dream = ""),
                            )
                        )
                    } catch (_: LanSessionException) { rejected = true }
                    assertTrue(rejected)

                    client.sendGameplay(
                        GameplayWireCommand.CharacterCreate(
                            "good-second",
                            "p2",
                            CharacterCreationTest.validDraft().copy(name = "Namiya"),
                        )
                    )
                    assertEquals("Namiya", host.state.players.getValue("p2").profile?.name)
                    assertEquals(host.state, clientReplica.state)
                }
            }
        }

        test("progression events survive dual autosave P2 restart and reconnect with identical hash") {
            val hostStore = SnapshotStore(Files.createTempDirectory("gld-progression-host"))
            val clientStore = SnapshotStore(Files.createTempDirectory("gld-progression-client"))
            val initial = initialWorld("char-progression-reconnect")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 77, snapshotStore = hostStore)
            val progression = CharacterProgressionCoordinator(host, snapshotStore = hostStore)
            val clientReplica = ClientReplica(initial)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()
                LanClientConnection("127.0.0.1", server.boundPort, "p2", clientReplica, clientStore).use { client ->
                    client.connect()
                    handler.handle(
                        GameplayWireCommand.CharacterCreate("pc-p1", "p1", CharacterCreationTest.validDraft()),
                        1000,
                    )
                    client.sendGameplay(
                        GameplayWireCommand.CharacterCreate(
                            "pc-p2",
                            "p2",
                            CharacterCreationTest.validDraft().copy(name = "Namiya"),
                        )
                    )

                    progression.awardEvolutionPoints("pev-p1", "p1", 5, 2000)
                    progression.recordAttributeTraining("train-p1-von", "p1", Attribute.VON, 2001)
                    progression.increaseAttribute("evolve-p1-von", "p1", Attribute.VON, 2002)
                    client.refresh()

                    val hostProfile = host.state.players.getValue("p1").profile!!
                    assertEquals(2, hostProfile.attributes.getValue(Attribute.VON))
                    assertEquals(2, hostProfile.evolutionPoints)
                    assertEquals(host.state, clientReplica.state)
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(clientReplica.state, clientStore.loadLatestValid())
                    client.disconnect()
                }

                val restartedState = clientStore.loadLatestValid()!!
                val restartedReplica = ClientReplica(restartedState)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", restartedReplica, clientStore).use { restarted ->
                    restarted.connect()
                    assertEquals(host.state, restartedReplica.state)
                    assertEquals(
                        CanonicalStateHasher.hash(host.state),
                        CanonicalStateHasher.hash(restartedReplica.state),
                    )
                }
            }
        }

        test("confirmed character cannot be overwritten by a different creation command") {
            val initial = initialWorld("char-coop-3")
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 77)
            handler.handle(
                GameplayWireCommand.CharacterCreate("first", "p1", CharacterCreationTest.validDraft()),
                1000,
            )
            var rejected = false
            try {
                handler.handle(
                    GameplayWireCommand.CharacterCreate(
                        "second",
                        "p1",
                        CharacterCreationTest.validDraft().copy(name = "Replacement"),
                    ),
                    1001,
                )
            } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals("Kairo", host.state.players.getValue("p1").profile?.name)
            assertEquals(1L, host.state.lastEventId)
        }
    }

    internal fun initialWorld(campaignId: String) = WorldState(
        campaignId = campaignId,
        islandId = "stormglass-cay",
        players = mapOf(
            "p1" to PlayerState("p1", "Player 1", 20, 20, 0),
            "p2" to PlayerState("p2", "Player 2", 20, 20, 0),
        ),
    )
}
