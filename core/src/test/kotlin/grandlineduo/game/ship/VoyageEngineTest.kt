package grandlineduo.game.ship

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object VoyageEngineTest {
    fun register() {
        test("voyage encounter requires exactly one locked action from each player") {
            val incident = VoyageIncident(VoyageIncidentType.STORM, severity = 2, seed = 99)
            var encounter = VoyageEncounter(incident)
            encounter = VoyageEngine.lockAction(encounter, "p1", VoyageAction.HELM)
            assertEquals(null, VoyageEngine.resolveIfReady(ShipEngine.starterShip("g", "Gull"), encounter))
            encounter = VoyageEngine.lockAction(encounter, "p2", VoyageAction.PROTECT_SUPPLIES)
            assertTrue(VoyageEngine.resolveIfReady(ShipEngine.starterShip("g", "Gull"), encounter) != null)

            var rejected = false
            try { VoyageEngine.lockAction(encounter, "p1", VoyageAction.CANNONS) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }

        test("four player voyage waits for every declared participant before resolving") {
            val incident = VoyageIncident(VoyageIncidentType.SEA_KING, severity = 3, seed = 404)
            var encounter = VoyageEncounter(
                incident = incident,
                participants = setOf("p1", "p2", "p3", "p4"),
            )
            encounter = VoyageEngine.lockAction(encounter, "p1", VoyageAction.HELM)
            encounter = VoyageEngine.lockAction(encounter, "p2", VoyageAction.CANNONS)
            encounter = VoyageEngine.lockAction(encounter, "p3", VoyageAction.REPAIR)
            assertEquals(null, VoyageEngine.resolveIfReady(ShipEngine.starterShip("g", "Gull"), encounter))

            var outsiderRejected = false
            try { VoyageEngine.lockAction(encounter, "p5", VoyageAction.LOOKOUT) } catch (_: IllegalArgumentException) { outsiderRejected = true }
            assertTrue(outsiderRejected)

            encounter = VoyageEngine.lockAction(encounter, "p4", VoyageAction.LOOKOUT)
            assertTrue(VoyageEngine.resolveIfReady(ShipEngine.starterShip("g", "Gull"), encounter) != null)
        }

        test("same ship incident seed and actions resolve voyage deterministically") {
            val ship = ShipEngine.starterShip("g", "Gull")
            val encounter = ready(
                VoyageIncident(VoyageIncidentType.PIRATE_AMBUSH, 3, 12345),
                VoyageAction.CANNONS,
                VoyageAction.HELM,
            )
            assertEquals(VoyageEngine.resolve(ship, encounter), VoyageEngine.resolve(ship, encounter))
        }

        test("helm plus protecting supplies is a storm synergy that limits losses") {
            val ship = ShipEngine.starterShip("g", "Gull")
            val incident = VoyageIncident(VoyageIncidentType.STORM, 4, 777)
            val coordinated = VoyageEngine.resolve(ship, ready(incident, VoyageAction.HELM, VoyageAction.PROTECT_SUPPLIES))
            val reckless = VoyageEngine.resolve(ship, ready(incident, VoyageAction.CANNONS, VoyageAction.LOOKOUT))
            assertEquals("STORM_RIDER", coordinated.coopSynergy)
            assertTrue(coordinated.hullDamage < reckless.hullDamage)
            assertTrue(coordinated.supplyLoss < reckless.supplyLoss)
        }

        test("cannons plus helm form a Sea King broadside synergy") {
            val ship = ShipEngine.starterShip("g", "Gull")
            val result = VoyageEngine.resolve(
                ship,
                ready(VoyageIncident(VoyageIncidentType.SEA_KING, 3, 8080), VoyageAction.CANNONS, VoyageAction.HELM),
            )
            assertEquals("SEA_KING_BROADSIDE", result.coopSynergy)
            assertTrue(result.shipAfter.hull >= 0)
            assertTrue(result.shipAfter.supplies >= 0)
        }

        test("lookout plus helm can evade a Marine interception") {
            val ship = ShipEngine.applyUpgrade(ShipEngine.starterShip("g", "Gull"), ShipUpgrade.SAILS)
            val result = VoyageEngine.resolve(
                ship,
                ready(VoyageIncident(VoyageIncidentType.MARINE_INTERCEPTION, 2, 41), VoyageAction.LOOKOUT, VoyageAction.HELM),
            )
            assertEquals("CLEAN_ESCAPE", result.coopSynergy)
            assertTrue(result.success)
        }
    }

    private fun ready(incident: VoyageIncident, p1: VoyageAction, p2: VoyageAction): VoyageEncounter {
        var encounter = VoyageEncounter(incident)
        encounter = VoyageEngine.lockAction(encounter, "p1", p1)
        encounter = VoyageEngine.lockAction(encounter, "p2", p2)
        return encounter
    }
}
