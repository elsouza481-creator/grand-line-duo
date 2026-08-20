package grandlineduo.game.ship

/** Typed world-condition flags derived from the current party ship for Director eligibility. */
object ShipDirectorBridge {
    const val SHIP_DAMAGED = "SHIP_DAMAGED"
    const val SHIP_CRITICAL = "SHIP_CRITICAL"
    const val SHIP_LOW_SUPPLIES = "SHIP_LOW_SUPPLIES"
    const val SHIP_NO_SUPPLIES = "SHIP_NO_SUPPLIES"
    const val SHIP_WELL_ARMED = "SHIP_WELL_ARMED"

    fun flagsFor(ship: ShipState): Set<String> = buildSet {
        val hullRatio = ship.hull.toDouble() / ship.maxHull.toDouble()
        val supplyRatio = if (ship.maxSupplies == 0) 0.0 else ship.supplies.toDouble() / ship.maxSupplies.toDouble()

        if (hullRatio <= 0.50) add(SHIP_DAMAGED)
        if (hullRatio <= 0.25) add(SHIP_CRITICAL)
        if (supplyRatio <= 0.25) add(SHIP_LOW_SUPPLIES)
        if (ship.supplies == 0) add(SHIP_NO_SUPPLIES)
        if (ship.artillery >= 5) add(SHIP_WELL_ARMED)
    }
}
