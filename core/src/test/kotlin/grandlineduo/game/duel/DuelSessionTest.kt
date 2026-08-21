package grandlineduo.game.duel

import grandlineduo.appshell.GameSessionCoordinator
import grandlineduo.appshell.GameSessionCoordinatorTest
import grandlineduo.appshell.SessionMode
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.game.arc.ArcArchetype
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object DuelSessionTest {
    fun register() {
        test("host session submit duel action reaches authoritative handler") {
            val root = Files.createTempDirectory("gld-duel-session-host")
            val campaignId = "duel-session-host"
            DurableCampaignStore(root.resolve(campaignId)).initialize(hostHubWorld(campaignId))

            GameSessionCoordinator(root).use { session ->
                session.resume(campaignId)
                assertEquals(SessionMode.HOST_COOP, session.mode)

                session.submitDuelAction("CHALLENGE")

                val duel = session.worldState().activeDuel!!
                assertEquals(DuelPhase.PENDING, duel.phase)
                assertEquals("p1", duel.challengerId)
                assertEquals("p2", duel.challengedId)
            }
        }

        test("solo session rejects duel challenge and companion never answers as pvp opponent") {
            val root = Files.createTempDirectory("gld-duel-session-solo")
            GameSessionCoordinator(root).use { session ->
                session.startSolo("duel-session-solo")
                session.createCharacter(GameSessionCoordinatorTest.validDraft("Solo Hero"))
                assertTrue(session.worldState().players.getValue("p2").profile != null)

                val result = runCatching { session.submitDuelAction("CHALLENGE") }

                assertTrue(result.isFailure)
                assertEquals(null, session.worldState().activeDuel)
                assertEquals("SOLO", session.worldState().worldFlags["campaign.mode"])
            }
        }
    }

    private fun hostHubWorld(campaignId: String): WorldState {
        val p1 = createdProfile("Arlen")
        val p2 = createdProfile("Mako")
        return WorldState(
            campaignId = campaignId,
            islandId = "ironwake-atoll",
            activeArc = ArcState(
                arcId = "arc-$campaignId",
                islandId = "ironwake-atoll",
                seed = 88L,
                archetype = ArcArchetype.ISLAND_CRISIS,
                phase = ArcPhase.COMPLETE,
            ),
            players = mapOf(
                "p1" to PlayerState("p1", p1.name, p1.maxHp, p1.maxHp, 0, p1.maxEnergy, p1.maxEnergy, p1),
                "p2" to PlayerState("p2", p2.name, p2.maxHp, p2.maxHp, 0, p2.maxEnergy, p2.maxEnergy, p2),
            ),
            worldFlags = mapOf("campaign.mode" to "HOST_COOP", "campaign.chapter" to "1"),
        )
    }

    private fun createdProfile(name: String) = when (
        val result = CharacterCreation.create(GameSessionCoordinatorTest.validDraft(name))
    ) {
        is CharacterCreationResult.Success -> result.profile
        is CharacterCreationResult.Invalid -> error(result.errors.joinToString())
    }
}
