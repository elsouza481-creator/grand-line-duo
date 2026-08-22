package grandlineduo.game.duel

import grandlineduo.appshell.GameSessionCoordinatorTest
import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanHostServer
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.arc.ArcArchetype
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object DuelLanIntegrationTest {
    fun register() {
        test("P2 duel survives reconnect converges and closes idempotently over real TCP") {
            val p1Profile = createdProfile("Kairo")
            val p2Profile = createdProfile("Namiya")
            val initial = WorldState(
                campaignId = "duel-lan-reconnect",
                islandId = "ironwake-atoll",
                partyBerries = 7_777L,
                activeArc = ArcState(
                    arcId = "arc-duel-lan",
                    islandId = "ironwake-atoll",
                    seed = 404L,
                    archetype = ArcArchetype.ISLAND_CRISIS,
                    phase = ArcPhase.COMPLETE,
                ),
                players = mapOf(
                    "p1" to PlayerState(
                        "p1", p1Profile.name, 180, 180, 12_345L,
                        9, 12, p1Profile,
                    ),
                    "p2" to PlayerState(
                        "p2", p2Profile.name, 60, 60, 23_456L,
                        8, 11, p2Profile,
                    ),
                ),
                worldFlags = mapOf(
                    "campaign.mode" to "HOST_COOP",
                    "inventory.marker" to "unchanged",
                ),
            )
            val berriesBefore = initial.partyBerries
            val bountiesBefore = initial.players.mapValues { it.value.bounty }
            val energyBefore = initial.players.mapValues { it.value.energy }
            val questBoardBefore = initial.questBoard
            val flagsBefore = initial.worldFlags

            val hostDir = Files.createTempDirectory("gld-duel-lan-host")
            val clientDir = Files.createTempDirectory("gld-duel-lan-client")
            val hostStore = SnapshotStore(hostDir)
            val clientStore = SnapshotStore(clientDir)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 9_901L, snapshotStore = hostStore)

            LanHostServer(host, port = 0, gameplayCommandHandler = handler).use { server ->
                server.start()

                val firstReplica = ClientReplica(initial)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", firstReplica, clientStore).use { firstClient ->
                    firstClient.connect()
                    firstClient.sendGameplay(
                        GameplayWireCommand.DuelAction("duel-challenge-p2", "p2", "CHALLENGE")
                    )
                    firstClient.refresh()

                    assertEquals(DuelPhase.PENDING, host.state.activeDuel!!.phase)
                    assertEquals(host.state, firstReplica.state)
                    assertEquals(
                        CanonicalStateHasher.hash(host.state),
                        CanonicalStateHasher.hash(firstReplica.state),
                    )

                    handler.handle(
                        GameplayWireCommand.DuelAction("duel-accept-p1", "p1", "ACCEPT"),
                        40_001L,
                    )
                    firstClient.refresh()
                    val active = host.state.activeDuel!!
                    assertEquals(DuelPhase.ACTIVE, active.phase)
                    assertEquals(1, active.round)
                    assertEquals(180, active.fighters.getValue("p1").hp)
                    assertEquals(60, active.fighters.getValue("p2").hp)
                    assertEquals(host.state, firstReplica.state)

                    handler.handle(
                        GameplayWireCommand.CombatAction(
                            "duel-round1-p1",
                            "p1",
                            CombatActionType.SETUP.name,
                        ),
                        40_002L,
                    )
                    assertEquals(setOf("p1"), host.state.activeDuel!!.lockedActions.keys)
                    firstClient.disconnect()
                }

                val persistedClientState = clientStore.loadLatestValid()!!
                assertTrue(persistedClientState.activeDuel!!.lockedActions.isEmpty())

                val reconnectedReplica = ClientReplica(persistedClientState)
                LanClientConnection("127.0.0.1", server.boundPort, "p2", reconnectedReplica, clientStore).use { client ->
                    client.connect()
                    client.refresh()

                    val reconnected = reconnectedReplica.state.activeDuel!!
                    assertEquals(1, reconnected.round)
                    assertEquals(
                        CombatActionType.SETUP,
                        reconnected.lockedActions.getValue("p1").type,
                    )
                    assertEquals(host.state.activeDuel!!.fighters, reconnected.fighters)
                    assertEquals(
                        CanonicalStateHasher.hash(host.state),
                        CanonicalStateHasher.hash(reconnectedReplica.state),
                    )

                    client.sendGameplay(
                        GameplayWireCommand.CombatAction(
                            "duel-round1-p2",
                            "p2",
                            CombatActionType.FINISHER.name,
                        )
                    )

                    var round = 2
                    while (host.state.activeDuel?.phase == DuelPhase.ACTIVE && round <= 20) {
                        handler.handle(
                            GameplayWireCommand.CombatAction(
                                "duel-round${round}-p1",
                                "p1",
                                CombatActionType.ATTACK.name,
                            ),
                            40_000L + round * 2,
                        )
                        client.sendGameplay(
                            GameplayWireCommand.CombatAction(
                                "duel-round${round}-p2",
                                "p2",
                                CombatActionType.FINISHER.name,
                            )
                        )
                        round++
                    }

                    client.refresh()
                    val finished = host.state.activeDuel!!
                    assertEquals(DuelPhase.FINISHED, finished.phase)
                    assertEquals(DuelFinishReason.KNOCKOUT, finished.finishReason)
                    val loserId = finished.loserId ?: error("Knockout duel must identify loser")
                    assertEquals(1, host.state.players.getValue(loserId).hp)
                    assertEquals(host.state, reconnectedReplica.state)
                    assertEquals(
                        CanonicalStateHasher.hash(host.state),
                        CanonicalStateHasher.hash(reconnectedReplica.state),
                    )
                    assertEquals(berriesBefore, host.state.partyBerries)
                    assertEquals(bountiesBefore, host.state.players.mapValues { it.value.bounty })
                    assertEquals(energyBefore, host.state.players.mapValues { it.value.energy })
                    assertEquals(questBoardBefore, host.state.questBoard)
                    assertEquals(flagsBefore, host.state.worldFlags)

                    val hpBeforeClose = host.state.players.mapValues { it.value.hp }
                    val close = GameplayWireCommand.DuelAction("duel-close-p2", "p2", "CLOSE")
                    client.sendGameplay(close)
                    client.refresh()

                    assertEquals(null, host.state.activeDuel)
                    assertEquals(null, reconnectedReplica.state.activeDuel)
                    assertEquals(hpBeforeClose, host.state.players.mapValues { it.value.hp })
                    assertEquals(energyBefore, host.state.players.mapValues { it.value.energy })
                    assertEquals(berriesBefore, host.state.partyBerries)
                    assertEquals(bountiesBefore, host.state.players.mapValues { it.value.bounty })
                    assertEquals(questBoardBefore, host.state.questBoard)
                    assertEquals(flagsBefore, host.state.worldFlags)
                    assertEquals(
                        CanonicalStateHasher.hash(host.state),
                        CanonicalStateHasher.hash(reconnectedReplica.state),
                    )

                    val eventIdAfterClose = host.state.lastEventId
                    client.sendGameplay(close)
                    client.refresh()
                    assertEquals(eventIdAfterClose, host.state.lastEventId)
                    assertEquals(null, host.state.activeDuel)
                    assertEquals(hpBeforeClose, host.state.players.mapValues { it.value.hp })
                    assertEquals(host.state, reconnectedReplica.state)
                    assertEquals(host.state, hostStore.loadLatestValid())
                    assertEquals(reconnectedReplica.state, clientStore.loadLatestValid())
                }
            }
        }
    }

    private fun createdProfile(name: String) = when (
        val result = CharacterCreation.create(GameSessionCoordinatorTest.validDraft(name))
    ) {
        is CharacterCreationResult.Success -> result.profile
        is CharacterCreationResult.Invalid -> error(result.errors.joinToString())
    }
}
