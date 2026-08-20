package grandlineduo.game.combat

import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine
import grandlineduo.game.powers.HakiType

object CombatModifierResolver {
    fun forWorld(world: WorldState): Map<String, CombatModifiers> = world.players.mapValues { (playerId, player) ->
        val item = InventoryEngine.combatBonus(world, playerId)
        val profile = player.profile
        val buso = profile?.haki?.disciplines?.get(HakiType.BUSOSHOKU)?.mastery ?: 0
        val ken = profile?.haki?.disciplines?.get(HakiType.KENBUNSHOKU)?.mastery ?: 0
        val hao = profile?.haki?.disciplines?.get(HakiType.HAOSHOKU)?.mastery ?: 0
        val fruit = profile?.devilFruit
        val fruitSuppressed = world.worldFlags["status.$playerId.seastone"] == "true" ||
            world.worldFlags["status.$playerId.submerged"] == "true"
        val fruitAttack = if (fruit != null && !fruitSuppressed) 2 + fruit.mastery else 0
        CombatModifiers(
            attackBonus = item.attackDamage + (buso * 2) + hao + fruitAttack,
            damageReduction = item.damageReduction + buso + ((ken + 1) / 2),
            busoshokuBonus = if (buso > 0) 4 + buso * 2 else 0,
            haoshokuBonus = if (hao > 0) 12 + hao * 4 else 0,
            devilFruitBonus = if (fruit != null && !fruitSuppressed) 5 + fruit.mastery * 3 else 0,
        )
    }
}
