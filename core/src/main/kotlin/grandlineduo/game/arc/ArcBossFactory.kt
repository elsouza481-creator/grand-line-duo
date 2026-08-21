package grandlineduo.game.arc

import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.*
import java.util.Random

object ArcBossFactory {
    fun create(world: WorldState, arc: ArcState): CombatState {
        require(arc.phase == ArcPhase.AFTERMATH || arc.phase == ArcPhase.CLIMAX) { "Arc is not at climax" }
        val party = world.players
            .filterKeys { it in HUMAN_PLAYER_IDS }
            .toSortedMap()
        require("p1" in party) { "Missing p1" }
        require("p2" in party) { "Missing p2" }
        require(party.size in 2..4) { "Arc boss requires two to four human players" }

        val spec = specFor(arc.archetype)
        val scholarTier = ClassMasteryArcResolver.scholarAnalysisTier(arc.sharedFlags)
        val hp = (spec.baseHp + arc.escalation * 8 - scholarTier * 6).coerceIn(1, 220)
        val attack = (spec.baseAttack + (arc.escalation + 1) / 2 - scholarTier).coerceIn(1, 28)
        val random = Random(combatSeed(arc))
        val partyIds = party.keys.toList()
        val target = if (partyIds == listOf("p1", "p2")) {
            if (random.nextBoolean()) "p1" else "p2"
        } else {
            partyIds[random.nextInt(partyIds.size)]
        }
        val type = if (random.nextBoolean()) EnemyAttackType.HEAVY_STRIKE else EnemyAttackType.SWEEP
        return CombatState(
            round = 1,
            players = party.mapValues { (id, player) ->
                Combatant(id, player.name, player.hp, player.maxHp)
            },
            enemy = EnemyCombatant(spec.id, spec.name, hp, hp, attack),
            telegraph = EnemyTelegraph(type, target),
            status = CombatStatus.ACTIVE,
        )
    }

    fun combatSeed(arc: ArcState): Long =
        arc.seed xor (arc.arcId.hashCode().toLong() shl 17) xor (arc.archetype.ordinal.toLong() * 0x9E3779B97F4A7C15UL.toLong())

    private data class BossSpec(val id: String, val name: String, val baseHp: Int, val baseAttack: Int)

    private fun specFor(archetype: ArcArchetype): BossSpec = when (archetype) {
        ArcArchetype.MARINE_OCCUPATION -> BossSpec("marine-commander-rook", "Comandante Rook", 128, 17)
        ArcArchetype.UNDERWORLD_SMUGGLING -> BossSpec("broker-vanta", "Corretor Vanta", 118, 18)
        ArcArchetype.PIRATE_TYRANNY -> BossSpec("captain-brask", "Capitão Brask", 136, 18)
        ArcArchetype.RUINS_MYSTERY -> BossSpec("basalt-guardian", "Guardião de Basalto", 150, 16)
        ArcArchetype.ISLAND_CRISIS -> BossSpec("crisis-enforcer", "Carrasco da Ruptura", 122, 17)
    }

    private val HUMAN_PLAYER_IDS = setOf("p1", "p2", "p3", "p4")
}
