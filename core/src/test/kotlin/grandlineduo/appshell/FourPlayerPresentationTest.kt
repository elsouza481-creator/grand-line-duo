package grandlineduo.appshell

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.world.ExplorationDirection
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.GridPosition
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object FourPlayerPresentationTest {
    fun register() {
        test("P3 hub presents all four party members and their independent map positions") {
            val profiles = (1..4).associate { index ->
                val id = "p$index"
                val profile = (
                    CharacterCreation.create(
                        GameSessionCoordinatorTest.validDraft("Player $index")
                    ) as CharacterCreationResult.Success
                ).profile
                id to profile
            }
            var world = WorldState(
                campaignId = "present-four-player",
                islandId = "stormglass-cay",
                players = profiles.mapValues { (id, profile) ->
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
                },
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
    }
}
