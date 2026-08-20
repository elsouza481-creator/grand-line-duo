package grandlineduo.game.ship

enum class ShipCompartment {
    STORAGE,
    KITCHEN,
    INFIRMARY,
    WORKSHOP,
    CANNON_DECK,
    OBSERVATION,
    CREW_QUARTERS,
}

enum class ShipUpgrade {
    HULL,
    SAILS,
    RUDDER,
    ARTILLERY,
    CARGO,
    SUPPLY_HOLDS,
}

data class ShipState(
    val shipId: String,
    val name: String,
    val hull: Int,
    val maxHull: Int,
    val speed: Int,
    val maneuverability: Int,
    val artillery: Int,
    val capacity: Int,
    val supplies: Int,
    val maxSupplies: Int,
    val compartments: Set<ShipCompartment> = emptySet(),
    val upgrades: Map<ShipUpgrade, Int> = emptyMap(),
) {
    init {
        require(shipId.isNotBlank()) { "Ship id is required" }
        require(name.isNotBlank()) { "Ship name is required" }
        require(maxHull > 0) { "Max hull must be positive" }
        require(hull in 0..maxHull) { "Hull must be within zero and max hull" }
        require(speed in 1..10) { "Speed must be in 1..10" }
        require(maneuverability in 1..10) { "Maneuverability must be in 1..10" }
        require(artillery in 1..10) { "Artillery must be in 1..10" }
        require(capacity in 1..10) { "Capacity must be in 1..10" }
        require(maxSupplies >= 0) { "Max supplies cannot be negative" }
        require(supplies in 0..maxSupplies) { "Supplies must be within zero and max supplies" }
        require(upgrades.values.all { it in 0..5 }) { "Ship upgrade level must be in 0..5" }
    }
}
