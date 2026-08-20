package grandlineduo.game.arc

import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.*
import java.util.Random

object ArcBossFactory {
    fun create(world: WorldState, arc: ArcState): CombatState {
        require(arc.phase == ArcPhase.AFTERMATH || arc.phase == ArcPhase.CLIMAX) { "Arc is not at climax" }
        val p1 = world.players["p1"] ?: throw IllegalArgumentException("Missing p1")
        val p2 = world.players["p2"] ?: throw IllegalArgumentException("Missing p2")
        val spec = specFor(arc.archetype)
        val hp = (spec.baseHp + arc.escalation * 8).coerceAtMost(220)
        val attack = (spec.baseAttack + (arc.escalation + 1) / 2).coerceAtMost(28)
        val random = Random(combatSeed(arc))
        val target = if (random.nextBoolean()) "p1" else "p2"
        val type = if (random.nextBoolean()) EnemyAttackType.HEAVY_STRIKE else EnemyAttackType.SWEEP
        return CombatState(
            round = 1,
            players = mapOf(
                "p1" to Combatant("p1", p1.name, p1.hp, p1.maxHp),
                "p2" to Combatant("p2", p2.name, p2.hp, p2.maxHp),
            ),
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
}
