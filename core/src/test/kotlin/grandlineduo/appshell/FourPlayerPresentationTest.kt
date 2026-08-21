package grandlineduo.appshell

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.arc.ArcEngine
import grandlineduo.game.arc.ArcStartContext
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterProfile
import grandlineduo.game.ship.VoyageAction
import grandlineduo.game.ship.VoyageEncounter
import grandlineduo.game.ship.VoyageIncident
import grandlineduo.game.ship.VoyageIncidentType
import grandlineduo.game.world.ExplorationDirection
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.GridPosition
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object FourPlayerPresentationTest {
    fun register() {
        test("P3 hub presents all four party members and their independent map positions") {
            val profiles = profiles(4)
            var world = WorldState(
                campaignId = "present-four-player",
                islandId = "stormglass-cay",
                players = players(profiles),
                worldFlags = mapOf("sg.stage" to "COMPLETE"),
            )
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val expectedPositions = linkedMapOf<String, GridPosition>(
                "p1" to map.spawn,
                "p2" to (map.spawn + ExplorationDirection.EAST),
                "p3" to (map.spawn + ExplorationDirection.WEST),
                "p4" to (map.spawn + ExplorationDirection.NORTH),
            )
            expectedPositions.forEach { (playerId, position) ->
                world = ExplorationEngine.place(world, playerId, position)
            }

            val presentation = GamePresenter.present(world, "p3")

            assertEquals(GameScreen.HUB, presentation.screen)
            assertEquals(expectedPositions, presentation.exploration?.playerPositions)
            assertEquals(expectedPositions.getValue("p3"), presentation.exploration?.playerPosition)
            assertTrue(presentation.status.any { it.contains("Tripulação 4/4") })
            assertTrue(presentation.status.any { it.contains("P1 Player 1") && it.contains("P2 Player 2") })
            assertTrue(presentation.status.any { it.contains("P3 Player 3") && it.contains("P4 Player 4") })
        }

        test("P3 and P4 receive real choices in the four player Stormglass opening") {
            val profiles = profiles(4)
            val world = WorldState(
                campaignId = "present-four-opening",
                islandId = "stormglass-cay",
                players = players(profiles),
            )

            listOf("p3", "p4").forEach { actorId ->
                val presentation = GamePresenter.present(world, actorId)
                assertEquals(GameScreen.STORY, presentation.screen)
                assertTrue(presentation.actions.isNotEmpty())
                assertTrue(presentation.actions.all { it.kind == "SCENARIO" })
                assertTrue(!presentation.title.contains("Observando"))
            }
        }

        test("P3 and P4 receive real decisions when the active narrative arc includes all four players") {
            val profiles = profiles(4)
            val participants = profiles.keys.toSortedSet()
            val arc = ArcEngine.start(
                ArcStartContext(
                    seed = 91L,
                    islandId = "stormglass-cay",
                    presentFactions = setOf("MARINES"),
                    worldFlags = emptySet(),
                    totalBounty = 0L,
                    participantIds = participants,
                )
            )
            val world = WorldState(
                campaignId = "present-four-arc",
                islandId = "stormglass-cay",
                players = players(profiles),
                worldFlags = mapOf("sg.stage" to "COMPLETE"),
                activeArc = arc,
            )

            listOf("p3", "p4").forEach { actorId ->
                val presentation = GamePresenter.present(world, actorId)
                assertEquals(GameScreen.ARC, presentation.screen)
                assertTrue(presentation.actions.isNotEmpty())
                assertTrue(presentation.actions.all { it.kind == "ARC" })
            }

            val afterP3 = ArcEngine.choose(arc, "p3", "shadow_authority").state
            val waiting = GamePresenter.present(world.copy(activeArc = afterP3), "p3")
            assertEquals(GameScreen.WAITING_FOR_PARTNER, waiting.screen)
            assertTrue(waiting.actions.isEmpty())
            assertTrue(waiting.body.contains("Aguardando"))
        }

        test("P3 and P4 receive voyage actions when they are declared four-player voyage participants") {
            val profiles = profiles(4)
            val encounter = VoyageEncounter(
                incident = VoyageIncident(VoyageIncidentType.STORM, severity = 3, seed = 404L),
                participants = profiles.keys.toSortedSet(),
            )
            val world = WorldState(
                campaignId = "present-four-voyage",
                islandId = "stormglass-cay",
                players = players(profiles),
                worldFlags = mapOf("sg.stage" to "COMPLETE"),
                activeVoyage = encounter,
            )

            listOf("p3", "p4").forEach { actorId ->
                val presentation = GamePresenter.present(world, actorId)
                assertEquals(GameScreen.VOYAGE, presentation.screen)
                assertTrue(presentation.actions.isNotEmpty())
                assertTrue(presentation.actions.all { it.kind == "VOYAGE" })
            }

            val locked = world.copy(
                activeVoyage = encounter.copy(actions = mapOf("p3" to VoyageAction.HELM)),
            )
            val waiting = GamePresenter.present(locked, "p3")
            assertEquals(GameScreen.WAITING_FOR_PARTNER, waiting.screen)
            assertTrue(waiting.actions.isEmpty())
            assertTrue(waiting.body.contains("Aguardando"))
        }

        test("four player arc and voyage screens expose authoritative decision progress") {
            val profiles = profiles(4)
            val participants = profiles.keys.toSortedSet()
            var arc = ArcEngine.start(
                ArcStartContext(
                    seed = 92L,
                    islandId = "stormglass-cay",
                    presentFactions = setOf("MARINES"),
                    worldFlags = emptySet(),
                    totalBounty = 0L,
                    participantIds = participants,
                )
            )
            arc = ArcEngine.choose(arc, "p1", "help_locals").state
            arc = ArcEngine.choose(arc, "p2", "survey_route").state
            val arcWorld = WorldState(
                campaignId = "present-progress-arc",
                islandId = "stormglass-cay",
                players = players(profiles),
                worldFlags = mapOf("sg.stage" to "COMPLETE"),
                activeArc = arc,
            )
            val arcPresentation = GamePresenter.present(arcWorld, "p3")
            assertEquals(GameScreen.ARC, arcPresentation.screen)
            assertTrue(arcPresentation.body.contains("2/4"))

            val encounter = VoyageEncounter(
                incident = VoyageIncident(VoyageIncidentType.SEA_KING, severity = 3, seed = 405L),
                participants = participants,
                actions = mapOf("p1" to VoyageAction.HELM, "p2" to VoyageAction.CANNONS),
            )
            val voyageWorld = arcWorld.copy(activeArc = null, activeVoyage = encounter)
            val voyagePresentation = GamePresenter.present(voyageWorld, "p3")
            assertEquals(GameScreen.VOYAGE, voyagePresentation.screen)
            assertTrue(voyagePresentation.body.contains("2/4"))

            val afterP3 = voyageWorld.copy(
                activeVoyage = encounter.copy(actions = encounter.actions + ("p3" to VoyageAction.LOOKOUT)),
            )
            val waiting = GamePresenter.present(afterP3, "p3")
            assertEquals(GameScreen.WAITING_FOR_PARTNER, waiting.screen)
            assertTrue(waiting.body.contains("3/4"))
        }
    }

    private fun profiles(count: Int): Map<String, CharacterProfile> = (1..count).associate { index ->
        val id = "p$index"
        val profile = (
            CharacterCreation.create(
                GameSessionCoordinatorTest.validDraft("Player $index")
            ) as CharacterCreationResult.Success
        ).profile
        id to profile
    }

    private fun players(profiles: Map<String, CharacterProfile>): Map<String, PlayerState> =
        profiles.mapValues { (id, profile) ->
            PlayerState(
                playerId = id,
                name = profile.name,
                hp = profile.maxHp,
                maxHp = profile.maxHp,
                bounty = 0,
                energy = profile.maxEnergy,
                maxEnergy = profile.maxEnergy,
                profile = profile,
            )
        }
}