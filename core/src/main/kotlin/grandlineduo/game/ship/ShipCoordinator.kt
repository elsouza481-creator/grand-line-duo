package grandlineduo.game.ship

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.core.persistence.SnapshotStore

/** Host-only boundary for acquiring, upgrading, and starting voyages with the party ship. */
class ShipCoordinator(
    private val hostReplica: HostReplica,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun acquireStarterShip(
        commandId: String,
        shipId: String,
        name: String,
        hostTimestamp: Long,
    ): CampaignEvent = changeWorld(
        commandId = commandId,
        fingerprint = "ship-acquire|$shipId|$name",
        hostTimestamp = hostTimestamp,
        metadata = mapOf("meta.ship" to "ACQUIRED", "meta.shipId" to shipId),
    ) { before ->
        require(before.shipState == null) { "Campaign already has a ship" }
        before.copy(shipState = ShipEngine.starterShip(shipId, name))
    }

    @Synchronized
    fun purchaseUpgrade(
        commandId: String,
        upgrade: ShipUpgrade,
        hostTimestamp: Long,
    ): CampaignEvent = changeWorld(
        commandId = commandId,
        fingerprint = "ship-upgrade|${upgrade.name}",
        hostTimestamp = hostTimestamp,
        metadata = mapOf("meta.ship" to "UPGRADED", "meta.shipUpgrade" to upgrade.name),
    ) { before ->
        require(before.activeVoyage == null) { "Cannot upgrade ship during an active voyage incident" }
        val ship = before.shipState ?: throw IllegalArgumentException("Campaign has no ship")
        val cost = ShipEngine.upgradeCost(ship, upgrade)
        require(before.partyBerries >= cost) { "Insufficient Berries for ship upgrade" }
        before.copy(
            partyBerries = before.partyBerries - cost,
            shipState = ShipEngine.applyUpgrade(ship, upgrade),
        )
    }

    @Synchronized
    fun repairAtPort(
        commandId: String,
        requestedHull: Int,
        hostTimestamp: Long,
    ): CampaignEvent = changeWorld(
        commandId = commandId,
        fingerprint = "ship-repair|$requestedHull",
        hostTimestamp = hostTimestamp,
        metadata = mapOf("meta.ship" to "REPAIRED"),
    ) { before ->
        require(before.activeVoyage == null) { "Cannot repair at port during a voyage incident" }
        require(requestedHull > 0) { "Requested repair must be positive" }
        val ship = before.shipState ?: throw IllegalArgumentException("Campaign has no ship")
        val actualRepair = minOf(requestedHull, ship.maxHull - ship.hull)
        require(actualRepair > 0) { "Ship does not need repair" }
        val cost = actualRepair.toLong() * REPAIR_COST_PER_HULL
        require(before.partyBerries >= cost) { "Insufficient Berries for repair" }
        before.copy(
            partyBerries = before.partyBerries - cost,
            shipState = ShipEngine.repair(ship, actualRepair),
        )
    }

    @Synchronized
    fun resupplyAtPort(
        commandId: String,
        requestedSupplies: Int,
        hostTimestamp: Long,
    ): CampaignEvent = changeWorld(
        commandId = commandId,
        fingerprint = "ship-resupply|$requestedSupplies",
        hostTimestamp = hostTimestamp,
        metadata = mapOf("meta.ship" to "RESUPPLIED"),
    ) { before ->
        require(before.activeVoyage == null) { "Cannot resupply at port during a voyage incident" }
        require(requestedSupplies > 0) { "Requested supplies must be positive" }
        val ship = before.shipState ?: throw IllegalArgumentException("Campaign has no ship")
        val actual = minOf(requestedSupplies, ship.maxSupplies - ship.supplies)
        require(actual > 0) { "Ship supplies are already full" }
        val cost = actual.toLong() * SUPPLY_COST_PER_UNIT
        require(before.partyBerries >= cost) { "Insufficient Berries for supplies" }
        before.copy(
            partyBerries = before.partyBerries - cost,
            shipState = ShipEngine.resupply(ship, actual),
        )
    }

    @Synchronized
    fun beginVoyage(
        commandId: String,
        incident: VoyageIncident,
        hostTimestamp: Long,
    ): CampaignEvent = changeWorld(
        commandId = commandId,
        fingerprint = "voyage-start|${incident.type.name}|${incident.severity}|${incident.seed}",
        hostTimestamp = hostTimestamp,
        metadata = mapOf(
            "meta.voyage" to "STARTED",
            "meta.voyageType" to incident.type.name,
            "meta.voyageSeverity" to incident.severity.toString(),
        ),
    ) { before ->
        val ship = before.shipState ?: throw IllegalArgumentException("Campaign has no ship")
        require(ship.hull > 0) { "Destroyed ship cannot begin voyage" }
        require(before.activeVoyage == null) { "Voyage incident already active" }
        before.copy(activeVoyage = VoyageEncounter(incident))
    }

    private fun changeWorld(
        commandId: String,
        fingerprint: String,
        hostTimestamp: Long,
        metadata: Map<String, String>,
        transform: (WorldState) -> WorldState,
    ): CampaignEvent {
        hostReplica.events.firstOrNull { it.commandId == commandId }?.let { existing ->
            require(existing.commandFingerprint == fingerprint) { "Command ID collision" }
            persist(existing)
            return existing
        }
        val nextWorld = transform(hostReplica.state)
        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = commandId,
                actorId = "gm",
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = metadata,
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun persist(event: CampaignEvent) {
        if (durableStore != null) durableStore.commit(event, hostReplica.state)
        else snapshotStore?.save(hostReplica.state)
    }

    companion object {
        const val REPAIR_COST_PER_HULL = 200L
        const val SUPPLY_COST_PER_UNIT = 100L
    }
}
