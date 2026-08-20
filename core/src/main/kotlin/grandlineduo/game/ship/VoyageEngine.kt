package grandlineduo.game.ship

import grandlineduo.game.character.CharacterProfile
import grandlineduo.game.crew.CrewEngine
import grandlineduo.game.crew.CrewRole
import grandlineduo.game.crew.CrewState
import java.util.Random

enum class VoyageIncidentType { STORM, SEA_KING, MARINE_INTERCEPTION, PIRATE_AMBUSH }
enum class VoyageAction { HELM, LOOKOUT, CANNONS, REPAIR, PROTECT_SUPPLIES }

data class VoyageIncident(
    val type: VoyageIncidentType,
    val severity: Int,
    val seed: Long,
) {
    init {
        require(severity in 1..5) { "Voyage incident severity must be in 1..5" }
    }
}

data class VoyageEncounter(
    val incident: VoyageIncident,
    val actions: Map<String, VoyageAction> = emptyMap(),
)

data class VoyageResolution(
    val success: Boolean,
    val hullDamage: Int,
    val supplyLoss: Int,
    val coopSynergy: String?,
    val shipAfter: ShipState,
)

object VoyageEngine {
    private val players = setOf("p1", "p2")

    fun lockAction(encounter: VoyageEncounter, playerId: String, action: VoyageAction): VoyageEncounter {
        require(playerId in players) { "Unknown voyage player $playerId" }
        require(playerId !in encounter.actions) { "$playerId already locked a voyage action" }
        return encounter.copy(actions = encounter.actions + (playerId to action))
    }

    fun resolveIfReady(
        ship: ShipState,
        encounter: VoyageEncounter,
        crew: CrewState = CrewState(),
        profiles: Map<String, CharacterProfile?> = emptyMap(),
    ): VoyageResolution? =
        if (encounter.actions.keys == players) resolve(ship, encounter, crew, profiles) else null

    fun resolve(
        ship: ShipState,
        encounter: VoyageEncounter,
        crew: CrewState = CrewState(),
        profiles: Map<String, CharacterProfile?> = emptyMap(),
    ): VoyageResolution {
        require(encounter.actions.keys == players) { "Both voyage actions must be locked before resolution" }
        val incident = encounter.incident
        val actions = encounter.actions.values.toSet()
        val synergy = synergyFor(incident.type, actions)

        val random = Random(incident.seed xor (incident.type.ordinal.toLong() * 0x9E3779B97F4A7C15UL.toLong()))
        val roll = random.nextInt(6)
        var actionScore = 0
        var hullMitigation = 0
        var supplyMitigation = 0

        encounter.actions.forEach { (playerId, action) ->
            val crewBonus = CrewEngine.bestCompetence(crew, roleFor(action))
            val classBonus = ClassMasteryVoyageResolver.resolve(profiles[playerId], action)
            when (action) {
                VoyageAction.HELM -> {
                    actionScore += ship.maneuverability * 2 + crewBonus * 2 + classBonus.actionScore
                    hullMitigation += ship.maneuverability + ship.speed / 2 + crewBonus * 2 + classBonus.hullMitigation
                }
                VoyageAction.LOOKOUT -> {
                    actionScore += 4 + crewBonus * 2 + classBonus.actionScore
                    hullMitigation += 1 + crewBonus + classBonus.hullMitigation
                    supplyMitigation += 2 + crewBonus + classBonus.supplyMitigation
                }
                VoyageAction.CANNONS -> {
                    actionScore += ship.artillery * 3 + crewBonus * 2 + classBonus.actionScore
                    if (incident.type in setOf(VoyageIncidentType.SEA_KING, VoyageIncidentType.PIRATE_AMBUSH)) {
                        hullMitigation += ship.artillery * 2 + crewBonus * 2 + classBonus.hullMitigation
                    }
                }
                VoyageAction.REPAIR -> {
                    actionScore += 3 + crewBonus + classBonus.actionScore
                    hullMitigation += 5 + crewBonus * 3 + classBonus.hullMitigation + if (ShipCompartment.WORKSHOP in ship.compartments) 3 else 0
                }
                VoyageAction.PROTECT_SUPPLIES -> {
                    actionScore += 2 + crewBonus + classBonus.actionScore
                    supplyMitigation += 12 + crewBonus * 3 + classBonus.supplyMitigation
                }
            }
        }

        val synergyScore = when (synergy) {
            "STORM_RIDER" -> 6
            "SEA_KING_BROADSIDE" -> 7
            "CLEAN_ESCAPE" -> 8
            "RUNNING_BROADSIDE" -> 6
            else -> 0
        }
        when (synergy) {
            "STORM_RIDER" -> {
                hullMitigation += 6
                supplyMitigation += 6
            }
            "SEA_KING_BROADSIDE" -> hullMitigation += 8
            "CLEAN_ESCAPE" -> {
                hullMitigation += 10
                supplyMitigation += 4
            }
            "RUNNING_BROADSIDE" -> hullMitigation += 6
        }

        val challenge = incident.severity * 5 + when (incident.type) {
            VoyageIncidentType.STORM -> 2
            VoyageIncidentType.SEA_KING -> 4
            VoyageIncidentType.MARINE_INTERCEPTION -> 3
            VoyageIncidentType.PIRATE_AMBUSH -> 2
        }
        var success = ship.speed + ship.maneuverability + actionScore + synergyScore + roll >= challenge
        if (synergy == "CLEAN_ESCAPE" && incident.severity <= 3) success = true

        val baseHullDamage = incident.severity * when (incident.type) {
            VoyageIncidentType.STORM -> 6
            VoyageIncidentType.SEA_KING -> 8
            VoyageIncidentType.MARINE_INTERCEPTION -> 5
            VoyageIncidentType.PIRATE_AMBUSH -> 6
        }
        val failurePenalty = if (success) 0 else incident.severity * 3
        val rawHullDamage = (baseHullDamage + failurePenalty - hullMitigation).coerceAtLeast(0)

        val baseSupplyLoss = 2 + CrewEngine.supplyUpkeep(crew) + incident.severity * when (incident.type) {
            VoyageIncidentType.STORM -> 4
            VoyageIncidentType.SEA_KING -> 2
            VoyageIncidentType.MARINE_INTERCEPTION -> 2
            VoyageIncidentType.PIRATE_AMBUSH -> 3
        }
        val rawSupplyLoss = (baseSupplyLoss - supplyMitigation).coerceAtLeast(0)
        val hullDamage = rawHullDamage.coerceAtMost(ship.hull)
        val supplyLoss = rawSupplyLoss.coerceAtMost(ship.supplies)
        val afterDamage = if (hullDamage > 0) ShipEngine.damage(ship, hullDamage) else ship
        val afterSupplies = if (supplyLoss > 0) ShipEngine.consumeSupplies(afterDamage, supplyLoss) else afterDamage

        return VoyageResolution(
            success = success,
            hullDamage = hullDamage,
            supplyLoss = supplyLoss,
            coopSynergy = synergy,
            shipAfter = afterSupplies,
        )
    }

    private fun roleFor(action: VoyageAction): CrewRole = when (action) {
        VoyageAction.HELM -> CrewRole.NAVIGATOR
        VoyageAction.LOOKOUT -> CrewRole.LOOKOUT
        VoyageAction.CANNONS -> CrewRole.GUNNER
        VoyageAction.REPAIR -> CrewRole.CARPENTER
        VoyageAction.PROTECT_SUPPLIES -> CrewRole.COOK
    }

    private fun synergyFor(type: VoyageIncidentType, actions: Set<VoyageAction>): String? = when {
        type == VoyageIncidentType.STORM &&
            VoyageAction.HELM in actions && VoyageAction.PROTECT_SUPPLIES in actions -> "STORM_RIDER"
        type == VoyageIncidentType.SEA_KING &&
            VoyageAction.CANNONS in actions && VoyageAction.HELM in actions -> "SEA_KING_BROADSIDE"
        type == VoyageIncidentType.MARINE_INTERCEPTION &&
            VoyageAction.LOOKOUT in actions && VoyageAction.HELM in actions -> "CLEAN_ESCAPE"
        type == VoyageIncidentType.PIRATE_AMBUSH &&
            VoyageAction.CANNONS in actions && VoyageAction.HELM in actions -> "RUNNING_BROADSIDE"
        else -> null
    }
}
