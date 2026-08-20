package grandlineduo.core.persistence

import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.model.WorldState
import java.nio.file.Path

fun interface DurableCommitFaultInjector {
    fun afterEventAppended(event: CampaignEvent)
}

class SimulatedDurableCommitCrash : RuntimeException("Simulated durable commit crash")

/**
 * Host-side durable commit boundary.
 *
 * Ordering is intentional: the accepted authoritative event is fsynced first, then the snapshot is
 * advanced. If the process dies between those writes, CampaignRecovery replays the durable event
 * from the previous snapshot. Re-committing the same event is idempotent.
 */
class DurableCampaignStore(
    private val directory: Path,
    private val faultInjector: DurableCommitFaultInjector = DurableCommitFaultInjector { },
) {
    private val snapshots = SnapshotStore(directory)
    private val log = EventLog(directory)

    @Synchronized
    fun initialize(initialState: WorldState) {
        val existing = snapshots.loadLatestValid()
        if (existing == null) {
            snapshots.save(initialState)
        } else {
            require(existing.campaignId == initialState.campaignId) { "Campaign mismatch" }
        }
    }

    @Synchronized
    fun commit(event: CampaignEvent, currentState: WorldState) {
        require(event.campaignId == currentState.campaignId) { "Campaign mismatch" }
        require(currentState.lastEventId >= event.eventId) { "Current state does not include event" }

        val existing = log.readValidPrefix(truncateIncompleteTail = true)
        val sameId = existing.firstOrNull { it.eventId == event.eventId }
        if (sameId != null) {
            require(sameId == event) { "Event ID collision in durable log" }
        } else {
            val lastId = existing.lastOrNull()?.eventId ?: 0L
            require(event.eventId == lastId + 1) {
                "Durable event sequence gap: expected ${lastId + 1}, got ${event.eventId}"
            }
            log.append(event)
        }

        faultInjector.afterEventAppended(event)
        snapshots.save(currentState)
    }

    fun recover(): RecoveredCampaign = CampaignRecovery(directory).recover()
}
