package grandlineduo.game.crew

import grandlineduo.game.ship.ShipEngine
import grandlineduo.game.ship.VoyageAction
import grandlineduo.game.ship.VoyageEncounter
import grandlineduo.game.ship.VoyageEngine
import grandlineduo.game.ship.VoyageIncident
import grandlineduo.game.ship.VoyageIncidentType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object CrewVoyageIntegrationTest {
    fun register() {
        test("specialized navigator and cook reduce deterministic storm losses") {
            val ship = ShipEngine.starterShip("g", "Gull")
            val encounter = ready(VoyageIncident(VoyageIncidentType.STORM, 5, 1414), VoyageAction.HELM, VoyageAction.PROTECT_SUPPLIES)
            val withoutCrew = VoyageEngine.resolve(ship, encounter, CrewState())
            val crew = CrewState(
                mapOf(
                    "mira" to member("mira", CrewRole.NAVIGATOR, 5),
                    "sanjiro" to member("sanjiro", CrewRole.COOK, 5),
                ),
            )
            val withCrew = VoyageEngine.resolve(ship, encounter, crew)
            assertTrue(withCrew.hullDamage < withoutCrew.hullDamage)
            assertTrue(withCrew.supplyLoss < withoutCrew.supplyLoss)
        }

        test("wounded specialist gives smaller voyage bonus than healthy specialist") {
            val ship = ShipEngine.starterShip("g", "Gull")
            val encounter = ready(VoyageIncident(VoyageIncidentType.SEA_KING, 5, 6161), VoyageAction.CANNONS, VoyageAction.HELM)
            val healthy = CrewState(mapOf("gunner" to member("gunner", CrewRole.GUNNER, 5)))
            val woundedMember = CrewEngine.injure(member("gunner", CrewRole.GUNNER, 5), 3)
            val wounded = CrewState(mapOf("gunner" to woundedMember))
            val healthyResult = VoyageEngine.resolve(ship, encounter, healthy)
            val woundedResult = VoyageEngine.resolve(ship, encounter, wounded)
            assertTrue(healthyResult.hullDamage <= woundedResult.hullDamage)
            assertTrue(healthyResult.success || !woundedResult.success)
        }

        test("captured specialist gives no voyage bonus") {
            val ship = ShipEngine.starterShip("g", "Gull")
            val encounter = ready(VoyageIncident(VoyageIncidentType.PIRATE_AMBUSH, 4, 333), VoyageAction.CANNONS, VoyageAction.HELM)
            val captured = CrewEngine.capture(member("gunner", CrewRole.GUNNER, 5))
            assertEquals(
                VoyageEngine.resolve(ship, encounter, CrewState()),
                VoyageEngine.resolve(ship, encounter, CrewState(mapOf("gunner" to captured))),
            )
        }

        test("duplicate role uses strongest available crew member only") {
            val ship = ShipEngine.starterShip("g", "Gull")
            val encounter = ready(VoyageIncident(VoyageIncidentType.MARINE_INTERCEPTION, 4, 9090), VoyageAction.LOOKOUT, VoyageAction.HELM)
            val bestOnly = CrewState(mapOf("ace" to member("ace", CrewRole.NAVIGATOR, 5)))
            val stacked = CrewState(
                mapOf(
                    "ace" to member("ace", CrewRole.NAVIGATOR, 5),
                    "rookie" to member("rookie", CrewRole.NAVIGATOR, 4),
                ),
            )
            val bestResult = VoyageEngine.resolve(ship, encounter, bestOnly)
            val stackedResult = VoyageEngine.resolve(ship, encounter, stacked)
            assertEquals(bestResult.hullDamage, stackedResult.hullDamage)
            assertEquals(bestResult.success, stackedResult.success)
            assertEquals(bestResult.supplyLoss + 1, stackedResult.supplyLoss)
        }

        test("active crew consumes voyage supplies while absent crew does not") {
            val ship = ShipEngine.starterShip("g", "Gull")
            val encounter = ready(VoyageIncident(VoyageIncidentType.SEA_KING, 3, 5151), VoyageAction.CANNONS, VoyageAction.HELM)
            val noCrew = VoyageEngine.resolve(ship, encounter, CrewState())
            val activeDoctor = CrewState(mapOf("doc" to member("doc", CrewRole.DOCTOR, 5)))
            val withActive = VoyageEngine.resolve(ship, encounter, activeDoctor)
            assertEquals(noCrew.supplyLoss + 1, withActive.supplyLoss)

            val captured = CrewEngine.capture(member("doc", CrewRole.DOCTOR, 5))
            val withCaptured = VoyageEngine.resolve(ship, encounter, CrewState(mapOf("doc" to captured)))
            assertEquals(noCrew.supplyLoss, withCaptured.supplyLoss)
        }

        test("same crew incident seed and actions remain deterministic") {
            val ship = ShipEngine.starterShip("g", "Gull")
            val encounter = ready(VoyageIncident(VoyageIncidentType.STORM, 4, 808), VoyageAction.HELM, VoyageAction.REPAIR)
            val crew = CrewState(
                mapOf(
                    "nav" to member("nav", CrewRole.NAVIGATOR, 4),
                    "carp" to member("carp", CrewRole.CARPENTER, 4),
                ),
            )
            assertEquals(VoyageEngine.resolve(ship, encounter, crew), VoyageEngine.resolve(ship, encounter, crew))
        }
    }

    private fun member(id: String, role: CrewRole, competence: Int) = CrewMemberState(
        npcId = id,
        name = id,
        role = role,
        competence = competence,
        loyalty = 20,
    )

    private fun ready(incident: VoyageIncident, p1: VoyageAction, p2: VoyageAction): VoyageEncounter {
        var encounter = VoyageEncounter(incident)
        encounter = VoyageEngine.lockAction(encounter, "p1", p1)
        encounter = VoyageEngine.lockAction(encounter, "p2", p2)
        return encounter
    }
}
