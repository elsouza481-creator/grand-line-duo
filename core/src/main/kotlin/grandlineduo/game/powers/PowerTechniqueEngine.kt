package grandlineduo.game.powers

import grandlineduo.core.model.WorldState
import grandlineduo.game.combat.CombatActionType

data class PowerTechnique(
    val id: String,
    val label: String,
    val energyCost: Int,
)

data class PreparedPowerAction(
    val world: WorldState,
    val combatAction: CombatActionType,
    val bonusDamage: Int,
    val technique: PowerTechnique,
)

object PowerTechniqueEngine {
    fun available(world: WorldState, playerId: String): List<PowerTechnique> {
        val profile = world.players[playerId]?.profile ?: return emptyList()
        return buildList {
            if (HakiType.BUSOSHOKU in profile.haki.disciplines) add(PowerTechnique("HAKI_BUSOSHOKU", "Busoshoku: Golpe Revestido", 4))
            if (HakiType.KENBUNSHOKU in profile.haki.disciplines) add(PowerTechnique("HAKI_KENBUNSHOKU", "Kenbunshoku: Leitura de Movimento", 3))
            if (HakiType.HAOSHOKU in profile.haki.disciplines) add(PowerTechnique("HAKI_HAOSHOKU", "Haoshoku: Onda de Vontade", 8))
            if (profile.devilFruit != null) add(PowerTechnique("DEVIL_FRUIT", "Técnica de Akuma no Mi", 5))
        }
    }

    fun prepare(world: WorldState, playerId: String, techniqueId: String): PreparedPowerAction {
        val player = world.players[playerId] ?: throw IllegalArgumentException("Unknown player $playerId")
        val profile = player.profile ?: throw IllegalArgumentException("Character not created for $playerId")
        val technique = available(world, playerId).firstOrNull { it.id == techniqueId }
            ?: throw IllegalArgumentException("Technique is not available")
        require(player.energy >= technique.energyCost) { "Insufficient energy" }

        val updatedProfile: grandlineduo.game.character.CharacterProfile
        val action: CombatActionType
        val bonus: Int
        when (technique.id) {
            "HAKI_BUSOSHOKU" -> {
                val discipline = profile.haki.disciplines.getValue(HakiType.BUSOSHOKU)
                updatedProfile = profile.copy(haki = HakiEngine.recordUse(profile.haki, HakiType.BUSOSHOKU))
                action = CombatActionType.HAKI_BUSOSHOKU
                bonus = 4 + discipline.mastery * 2
            }
            "HAKI_KENBUNSHOKU" -> {
                updatedProfile = profile.copy(haki = HakiEngine.recordUse(profile.haki, HakiType.KENBUNSHOKU))
                action = CombatActionType.HAKI_KENBUNSHOKU
                bonus = 0
            }
            "HAKI_HAOSHOKU" -> {
                val discipline = profile.haki.disciplines.getValue(HakiType.HAOSHOKU)
                updatedProfile = profile.copy(haki = HakiEngine.recordUse(profile.haki, HakiType.HAOSHOKU))
                action = CombatActionType.HAKI_HAOSHOKU
                bonus = 12 + discipline.mastery * 4
            }
            "DEVIL_FRUIT" -> {
                require(world.worldFlags["status.$playerId.seastone"] != "true") { "Kairouseki suppresses the Devil Fruit" }
                require(world.worldFlags["status.$playerId.submerged"] != "true") { "Submersion suppresses the Devil Fruit" }
                val fruit = profile.devilFruit ?: throw IllegalArgumentException("Character has no Devil Fruit")
                updatedProfile = profile.copy(devilFruit = DevilFruitEngine.recordUse(fruit))
                action = CombatActionType.DEVIL_FRUIT
                bonus = 5 + fruit.mastery * 3
            }
            else -> throw IllegalArgumentException("Unknown technique ${technique.id}")
        }
        val updatedPlayer = player.copy(
            energy = player.energy - technique.energyCost,
            profile = updatedProfile,
        )
        return PreparedPowerAction(
            world = world.copy(players = world.players + (playerId to updatedPlayer)),
            combatAction = action,
            bonusDamage = bonus,
            technique = technique,
        )
    }
}
