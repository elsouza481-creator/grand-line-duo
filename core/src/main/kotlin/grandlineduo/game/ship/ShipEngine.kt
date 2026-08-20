package grandlineduo.game.ship

object ShipEngine {
    fun starterShip(shipId: String, name: String): ShipState = ShipState(
        shipId = shipId,
        name = name,
        hull = 60,
        maxHull = 60,
        speed = 3,
        maneuverability = 3,
        artillery = 2,
        capacity = 4,
        supplies = 40,
        maxSupplies = 40,
        compartments = setOf(ShipCompartment.STORAGE),
    )

    fun damage(ship: ShipState, amount: Int): ShipState {
        require(amount > 0) { "Damage must be positive" }
        return ship.copy(hull = (ship.hull - amount).coerceAtLeast(0))
    }

    fun repair(ship: ShipState, amount: Int): ShipState {
        require(amount > 0) { "Repair amount must be positive" }
        return ship.copy(hull = (ship.hull + amount).coerceAtMost(ship.maxHull))
    }

    fun consumeSupplies(ship: ShipState, amount: Int): ShipState {
        require(amount > 0) { "Supply consumption must be positive" }
        return ship.copy(supplies = (ship.supplies - amount).coerceAtLeast(0))
    }

    fun resupply(ship: ShipState, amount: Int): ShipState {
        require(amount > 0) { "Resupply amount must be positive" }
        return ship.copy(supplies = (ship.supplies + amount).coerceAtMost(ship.maxSupplies))
    }

    fun installCompartment(ship: ShipState, compartment: ShipCompartment): ShipState {
        require(compartment !in ship.compartments) { "$compartment already installed" }
        require(ship.compartments.size < ship.capacity) { "No free compartment capacity" }
        return ship.copy(compartments = ship.compartments + compartment)
    }

    fun upgradeCost(ship: ShipState, upgrade: ShipUpgrade): Long {
        val current = ship.upgrades[upgrade] ?: 0
        require(current < 5) { "$upgrade is already at maximum level" }
        val nextLevel = current + 1L
        val base = when (upgrade) {
            ShipUpgrade.HULL -> 30_000L
            ShipUpgrade.SAILS -> 25_000L
            ShipUpgrade.RUDDER -> 22_000L
            ShipUpgrade.ARTILLERY -> 35_000L
            ShipUpgrade.CARGO -> 20_000L
            ShipUpgrade.SUPPLY_HOLDS -> 18_000L
        }
        return base * nextLevel * nextLevel
    }

    fun applyUpgrade(ship: ShipState, upgrade: ShipUpgrade): ShipState {
        val current = ship.upgrades[upgrade] ?: 0
        require(current < 5) { "$upgrade is already at maximum level" }
        val levels = ship.upgrades + (upgrade to current + 1)
        return when (upgrade) {
            ShipUpgrade.HULL -> ship.copy(
                hull = ship.hull + 20,
                maxHull = ship.maxHull + 20,
                upgrades = levels,
            )
            ShipUpgrade.SAILS -> ship.copy(
                speed = (ship.speed + 1).coerceAtMost(10),
                upgrades = levels,
            )
            ShipUpgrade.RUDDER -> ship.copy(
                maneuverability = (ship.maneuverability + 1).coerceAtMost(10),
                upgrades = levels,
            )
            ShipUpgrade.ARTILLERY -> ship.copy(
                artillery = (ship.artillery + 1).coerceAtMost(10),
                upgrades = levels,
            )
            ShipUpgrade.CARGO -> ship.copy(
                capacity = (ship.capacity + 1).coerceAtMost(10),
                upgrades = levels,
            )
            ShipUpgrade.SUPPLY_HOLDS -> ship.copy(
                supplies = ship.supplies + 20,
                maxSupplies = ship.maxSupplies + 20,
                upgrades = levels,
            )
        }
    }
}
