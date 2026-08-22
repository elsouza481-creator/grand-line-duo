package grandlineduo.game.duel

import grandlineduo.appshell.GamePresenter
import grandlineduo.appshell.GameScreen
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.arc.ArcArchetype
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.appshell.GameSessionCoordinatorTest
import grandlineduo.game.combat.CombatAction
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.powers.HakiDiscipline
import grandlineduo.game.powers.HakiType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object DuelPresentationTest {
    fun register() {
        test("host coop hub exposes duel challenge while solo hub does not") {
            val coop = hubWorld("duel-present-hub", "HOST_COOP")
            val solo = hubWorld("duel-present-solo", "SOLO")

            val p1 = GamePresenter.present(coop, "p1")
            val p2 = GamePresenter.present(coop, "p2")
            val soloView = GamePresenter.present(solo, "p1")

            assertTrue(p1.actions.any { it.id == "CHALLENGE" && it.label == "Desafiar para duelo" && it.kind == "DUEL" })
            assertTrue(p2.actions.any { it.id == "CHALLENGE" && it.kind == "DUEL" })
            assertTrue(soloView.actions.none { it.kind == "DUEL" })
        }

        test("pending challenger waits while challenged player gets accept and decline only") {
            val base = hubWorld("duel-present-pending", "HOST_COOP")
            val world = base.copy(
                activeDuel = DuelState("duel-pending", "p1", "p2", DuelPhase.PENDING)
            )

            val challenger = GamePresenter.present(world, "p1")
            val challenged = GamePresenter.present(world, "p2")

            assertEquals(GameScreen.WAITING_FOR_PARTNER, challenger.screen)
            assertTrue(challenger.actions.isEmpty())
            assertEquals(GameScreen.DUEL, challenged.screen)
            assertEquals(setOf("ACCEPT", "DECLINE"), challenged.actions.map { it.id }.toSet())
            assertTrue(challenged.actions.all { it.kind == "DUEL" })
        }

        test("active duel exposes basic combat and available powers to unlocked actor") {
            val base = hubWorld("duel-present-active", "HOST_COOP")
            val p1 = base.players.getValue("p1")
            val profile = p1.profile!!.copy(
                haki = p1.profile.haki.copy(
                    disciplines = p1.profile.haki.disciplines + (HakiType.BUSOSHOKU to HakiDiscipline(1, 0))
                )
            )
            val powered = base.copy(players = base.players + ("p1" to p1.copy(profile = profile, energy = 10, maxEnergy = 10)))
            val world = powered.copy(activeDuel = activeDuel(powered))

            val view = GamePresenter.present(world, "p1")

            assertEquals(GameScreen.DUEL, view.screen)
            assertTrue(view.actions.any { it.id == CombatActionType.ATTACK.name && it.kind == "COMBAT" })
            assertTrue(view.actions.any { it.id == "HAKI_BUSOSHOKU" && it.kind == "POWER" })
            assertTrue(view.body.contains("Rodada 1"))
            assertTrue(view.body.contains("PV"))
        }

        test("locked duel actor waits and presentation never reveals opponent action type") {
            val base = hubWorld("duel-present-locked", "HOST_COOP")
            val duel = activeDuel(base).copy(
                lockedActions = mapOf(
                    "p1" to CombatAction("p1", CombatActionType.FINISHER),
                    "p2" to CombatAction("p2", CombatActionType.DEFEND),
                )
            )
            val world = base.copy(activeDuel = duel)

            val view = GamePresenter.present(world, "p1")

            assertEquals(GameScreen.WAITING_FOR_PARTNER, view.screen)
            assertTrue(view.actions.isEmpty())
            assertTrue(view.body.contains("Oponente pronto"))
            assertTrue(!view.body.contains("DEFEND"))
            assertTrue(!view.body.contains("FINISHER"))
        }

        test("finished knockout names winner and loser and exposes close") {
            val base = hubWorld("duel-present-finished", "HOST_COOP")
            val duel = activeDuel(base).copy(
                phase = DuelPhase.FINISHED,
                fighters = activeDuel(base).fighters + ("p2" to activeDuel(base).fighters.getValue("p2").copy(hp = 1)),
                winnerId = "p1",
                loserId = "p2",
                finishReason = DuelFinishReason.KNOCKOUT,
            )
            val view = GamePresenter.present(base.copy(activeDuel = duel), "p1")

            assertEquals(GameScreen.DUEL, view.screen)
            assertTrue(view.body.contains(base.players.getValue("p1").name))
            assertTrue(view.body.contains(base.players.getValue("p2").name))
            assertTrue(view.actions.any { it.id == "CLOSE" && it.label == "Encerrar duelo" && it.kind == "DUEL" })
        }

        test("finished double knockout presents deterministic draw text") {
            val base = hubWorld("duel-present-double", "HOST_COOP")
            val duel = activeDuel(base).copy(
                phase = DuelPhase.FINISHED,
                fighters = activeDuel(base).fighters.mapValues { (_, fighter) -> fighter.copy(hp = 1) },
                winnerId = null,
                loserId = null,
                finishReason = DuelFinishReason.DOUBLE_KNOCKOUT,
            )
            val view = GamePresenter.present(base.copy(activeDuel = duel), "p2")

            assertEquals(GameScreen.DUEL, view.screen)
            assertTrue(view.body.contains("Empate — nocaute duplo"))
            assertTrue(view.actions.any { it.id == "CLOSE" && it.kind == "DUEL" })
        }
    }

    private fun hubWorld(id: String, mode: String): WorldState {
        val p1Profile = createdProfile("Arlen")
        val p2Profile = createdProfile("Mako")
        return WorldState(
            campaignId = id,
            islandId = "ironwake-atoll",
            activeArc = ArcState(
                arcId = "arc-$id",
                islandId = "ironwake-atoll",
                seed = 19L,
                archetype = ArcArchetype.ISLAND_CRISIS,
                phase = ArcPhase.COMPLETE,
            ),
            players = mapOf(
                "p1" to PlayerState("p1", p1Profile.name, p1Profile.maxHp, p1Profile.maxHp, 0, p1Profile.maxEnergy, p1Profile.maxEnergy, p1Profile),
                "p2" to PlayerState("p2", p2Profile.name, p2Profile.maxHp, p2Profile.maxHp, 0, p2Profile.maxEnergy, p2Profile.maxEnergy, p2Profile),
            ),
            worldFlags = mapOf("campaign.mode" to mode),
        )
    }

    private fun activeDuel(world: WorldState) = DuelState(
        duelId = "active-${world.campaignId}",
        challengerId = "p1",
        challengedId = "p2",
        phase = DuelPhase.ACTIVE,
        round = 1,
        fighters = world.players.mapValues { (id, player) ->
            DuelFighter(id, player.name, player.hp, player.maxHp)
        },
    )

    private fun createdProfile(name: String) = when (
        val result = CharacterCreation.create(GameSessionCoordinatorTest.validDraft(name))
    ) {
        is CharacterCreationResult.Success -> result.profile
        is CharacterCreationResult.Invalid -> error(result.errors.joinToString())
    }
}
