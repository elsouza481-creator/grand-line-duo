package grandlineduo.game.director

import grandlineduo.game.crew.CrewDirectorBridge
import grandlineduo.game.ship.ShipDirectorBridge

import java.util.Random

enum class DirectorDifficulty { RELAXED, NORMAL, VETERAN, BRUTAL }
enum class DirectorEventKind { THREAT, OPPORTUNITY, RELIEF, RUMOR }

data class DirectorContext(
    val seed: Long,
    val decisionIndex: Long,
    val islandId: String,
    val difficulty: DirectorDifficulty,
    val totalBounty: Long,
    val currentPartyHp: Int,
    val maxPartyHp: Int,
    val presentFactions: Set<String>,
    val worldFlags: Set<String>,
    val recentEventIds: List<String>,
)

data class DirectorEvent(
    val id: String,
    val title: String,
    val kind: DirectorEventKind,
    val threatCost: Int,
    val requiredFaction: String? = null,
    val requiredFlag: String? = null,
    val blockedByFlag: String? = null,
)

data class DirectorDecision(
    val event: DirectorEvent,
    val threatBudget: Int,
)

class GrandLineDirector(
    private val catalog: List<DirectorEvent> = defaultCatalog(),
) {
    fun threatBudget(context: DirectorContext): Int {
        val base = when (context.difficulty) {
            DirectorDifficulty.RELAXED -> 3
            DirectorDifficulty.NORMAL -> 5
            DirectorDifficulty.VETERAN -> 7
            DirectorDifficulty.BRUTAL -> 9
        }
        val bountyBonus = (context.totalBounty / 10_000_000L).coerceAtMost(5).toInt()
        val hpRatio = if (context.maxPartyHp <= 0) 0.0
        else context.currentPartyHp.toDouble() / context.maxPartyHp.toDouble()
        val healthPenalty = when {
            hpRatio < 0.25 -> 4
            hpRatio < 0.40 -> 3
            hpRatio < 0.60 -> 1
            else -> 0
        }
        return (base + bountyBonus - healthPenalty).coerceAtLeast(0)
    }

    fun choose(context: DirectorContext): DirectorDecision? {
        val budget = threatBudget(context)
        val eligible = catalog.filter { event ->
            event.threatCost <= budget &&
                (event.requiredFaction == null || event.requiredFaction in context.presentFactions) &&
                (event.requiredFlag == null || event.requiredFlag in context.worldFlags) &&
                (event.blockedByFlag == null || event.blockedByFlag !in context.worldFlags) &&
                event.id !in context.recentEventIds.takeLast(4)
        }
        if (eligible.isEmpty()) return null

        val hpRatio = if (context.maxPartyHp <= 0) 0.0
        else context.currentPartyHp.toDouble() / context.maxPartyHp.toDouble()
        val preferred = if (hpRatio < 0.35) {
            eligible.filter { it.kind == DirectorEventKind.RELIEF }.ifEmpty { eligible }
        } else {
            eligible
        }
        val random = Random(context.seed xor (context.decisionIndex * 0x9E3779B97F4A7C15UL.toLong()))
        return DirectorDecision(preferred[random.nextInt(preferred.size)], budget)
    }

    companion object {
        fun defaultCatalog(): List<DirectorEvent> = listOf(
            DirectorEvent("marine-patrol", "Patrulha da Marinha", DirectorEventKind.THREAT, 3, "MARINES"),
            DirectorEvent(
                "marine-reinforced-patrol",
                "Patrulha reforçada da Marinha",
                DirectorEventKind.THREAT,
                4,
                "MARINES",
                "MARINE_RESPONSE_REINFORCED",
            ),
            DirectorEvent(
                "marine-captain-unit",
                "Unidade comandada por Capitão da Marinha",
                DirectorEventKind.THREAT,
                5,
                "MARINES",
                "MARINE_RESPONSE_CAPTAIN",
            ),
            DirectorEvent(
                "marine-specialist-unit",
                "Unidade especializada da Marinha",
                DirectorEventKind.THREAT,
                7,
                "MARINES",
                "MARINE_RESPONSE_SPECIALIST",
            ),
            DirectorEvent(
                "marine-vice-admiral-sighting",
                "Vice-Almirante em operação",
                DirectorEventKind.THREAT,
                9,
                "MARINES",
                "MARINE_RESPONSE_VICE_ADMIRAL",
            ),
            DirectorEvent("bounty-hunters", "Caçadores de recompensa", DirectorEventKind.THREAT, 4, "BOUNTY_HUNTERS"),
            DirectorEvent("storm-shelter", "Abrigo contra a tempestade", DirectorEventKind.RELIEF, 0),
            DirectorEvent(
                "hidden-repair-cove",
                "Enseada protegida para reparos",
                DirectorEventKind.RELIEF,
                0,
                requiredFlag = ShipDirectorBridge.SHIP_DAMAGED,
            ),
            DirectorEvent(
                "drifting-supply-wreckage",
                "Destroços com suprimentos à deriva",
                DirectorEventKind.RELIEF,
                0,
                requiredFlag = ShipDirectorBridge.SHIP_LOW_SUPPLIES,
            ),
            DirectorEvent(
                "armed-rival-challenge",
                "Navio rival mede forças com a tripulação",
                DirectorEventKind.THREAT,
                6,
                requiredFlag = ShipDirectorBridge.SHIP_WELL_ARMED,
            ),
            DirectorEvent("black-market-rumor", "Rumor do mercado negro", DirectorEventKind.RUMOR, 1, "UNDERWORLD"),
            DirectorEvent(
                "trusted-contact-tipoff",
                "Aviso de um contato de confiança",
                DirectorEventKind.OPPORTUNITY,
                1,
                requiredFlag = "SOCIAL_HAS_ALLY",
            ),
            DirectorEvent(
                "old-rival-trail",
                "Rastro deixado por um antigo rival",
                DirectorEventKind.RUMOR,
                1,
                requiredFlag = "SOCIAL_HAS_RIVAL",
            ),
            DirectorEvent(
                "hostile-faction-pressure",
                "Pressão de uma facção hostil local",
                DirectorEventKind.THREAT,
                4,
                requiredFlag = "SOCIAL_PRESENT_HOSTILE_FACTION",
            ),
            DirectorEvent(
                "crew-field-treatment",
                "Tratamento de campo conduzido pela tripulação",
                DirectorEventKind.RELIEF,
                0,
                requiredFlag = CrewDirectorBridge.HAS_DOCTOR,
            ),
            DirectorEvent(
                "crew-emergency-repair",
                "Reparo de emergência conduzido pelo carpinteiro",
                DirectorEventKind.RELIEF,
                0,
                requiredFlag = CrewDirectorBridge.HAS_CARPENTER,
            ),
            DirectorEvent(
                "crew-secret-route",
                "Rota alternativa descoberta pelo navegador",
                DirectorEventKind.OPPORTUNITY,
                1,
                requiredFlag = CrewDirectorBridge.HAS_NAVIGATOR,
            ),
            DirectorEvent(
                "crew-loyalty-crisis",
                "Tensão de lealdade dentro da tripulação",
                DirectorEventKind.THREAT,
                3,
                requiredFlag = CrewDirectorBridge.LOW_LOYALTY,
            ),
            DirectorEvent(
                "crew-rescue-lead",
                "Pista sobre um tripulante capturado",
                DirectorEventKind.OPPORTUNITY,
                1,
                requiredFlag = CrewDirectorBridge.MEMBER_CAPTURED,
            ),
            DirectorEvent(
                "crew-search-trail",
                "Vestígios de um tripulante desaparecido",
                DirectorEventKind.OPPORTUNITY,
                1,
                requiredFlag = CrewDirectorBridge.MEMBER_MISSING,
            ),
            DirectorEvent("stranded-sailor", "Marinheiro encalhado", DirectorEventKind.OPPORTUNITY, 1),
        )
    }
}
