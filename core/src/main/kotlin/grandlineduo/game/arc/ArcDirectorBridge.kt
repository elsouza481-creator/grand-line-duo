package grandlineduo.game.arc

import grandlineduo.core.model.WorldState
import grandlineduo.game.crew.CrewDirectorBridge
import grandlineduo.game.ship.ShipDirectorBridge
import grandlineduo.game.social.SocialDirectorBridge
import grandlineduo.game.social.SocialWorldFlags

object ArcDirectorBridge {
    const val ALLIED_CONTACT_AVAILABLE = "ARC_ALLIED_CONTACT_AVAILABLE"
    const val RESOURCE_PRESSURE = "ARC_RESOURCE_PRESSURE"

    fun contextFor(
        world: WorldState,
        seed: Long,
        presentFactions: Set<String>,
    ): ArcStartContext {
        val flags = buildSet {
            world.worldFlags.filterValues { it.equals("true", ignoreCase = true) || it == "1" }
                .keys.forEach(::add)
            SocialDirectorBridge.flagsFor(world.socialState, presentFactions).forEach(::add)
            CrewDirectorBridge.flagsFor(world.crewState).forEach(::add)
            world.shipState?.let { ShipDirectorBridge.flagsFor(it).forEach(::add) }
            if (SocialWorldFlags.HAS_ALLY in this || SocialDirectorBridge.PRESENT_ALLIED_FACTION in this) {
                add(ALLIED_CONTACT_AVAILABLE)
            }
            if (ShipDirectorBridge.SHIP_LOW_SUPPLIES in this || ShipDirectorBridge.SHIP_NO_SUPPLIES in this) {
                add(RESOURCE_PRESSURE)
            }
        }
        return ArcStartContext(
            seed = seed,
            islandId = world.islandId,
            presentFactions = presentFactions.toSortedSet(),
            worldFlags = flags,
            totalBounty = world.players.values.sumOf { it.bounty },
        )
    }
}
