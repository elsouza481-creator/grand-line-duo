package grandlineduo.game.ship

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ShipEngineTest {
    fun register() {
        test("starter vessel has bounded usable core stats") {
            val ship = ShipEngine.starterShip("black-gull", "Black Gull")
            assertEquals(60, ship.hull)
            assertEquals(60, ship.maxHull)
            assertEquals(40, ship.supplies)
            assertTrue(ship.speed in 1..10)
            assertTrue(ship.maneuverability in 1..10)
            assertTrue(ship.capacity in 1..10)
        }

        test("ship damage and repair clamp between zero and max hull") {
            val ship = ShipEngine.starterShip("black-gull", "Black Gull")
            val wrecked = ShipEngine.damage(ship, 999)
            assertEquals(0, wrecked.hull)
            val repaired = ShipEngine.repair(wrecked, 999)
            assertEquals(ship.maxHull, repaired.hull)
        }

        test("resupply cannot exceed ship supply capacity") {
            val ship = ShipEngine.starterShip("black-gull", "Black Gull").copy(supplies = 3)
            val full = ShipEngine.resupply(ship, 500)
            assertEquals(ship.maxSupplies, full.supplies)
            val spent = ShipEngine.consumeSupplies(full, 999)
            assertEquals(0, spent.supplies)
        }

        test("sail upgrade deterministically increases speed and records level") {
            val ship = ShipEngine.starterShip("black-gull", "Black Gull")
            val upgraded = ShipEngine.applyUpgrade(ship, ShipUpgrade.SAILS)
            assertEquals(ship.speed + 1, upgraded.speed)
            assertEquals(1, upgraded.upgrades.getValue(ShipUpgrade.SAILS))
            assertTrue(ShipEngine.upgradeCost(ship, ShipUpgrade.SAILS) > 0)
        }

        test("ship upgrade is capped at level five") {
            var ship = ShipEngine.starterShip("black-gull", "Black Gull")
            repeat(5) { ship = ShipEngine.applyUpgrade(ship, ShipUpgrade.HULL) }
            assertEquals(5, ship.upgrades.getValue(ShipUpgrade.HULL))
            var rejected = false
            try { ShipEngine.applyUpgrade(ship, ShipUpgrade.HULL) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }

        test("installing duplicate compartment is rejected") {
            val ship = ShipEngine.starterShip("black-gull", "Black Gull")
            val withKitchen = ShipEngine.installCompartment(ship, ShipCompartment.KITCHEN)
            var rejected = false
            try { ShipEngine.installCompartment(withKitchen, ShipCompartment.KITCHEN) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }
}
