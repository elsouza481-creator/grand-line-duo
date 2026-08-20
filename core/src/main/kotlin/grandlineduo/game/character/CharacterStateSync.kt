package grandlineduo.game.character

import grandlineduo.core.model.PlayerState

object CharacterStateSync {
    fun applyProfile(player: PlayerState, profile: CharacterProfile): PlayerState {
        val hpDeficit = (player.maxHp - player.hp).coerceAtLeast(0)
        val energyDeficit = (player.maxEnergy - player.energy).coerceAtLeast(0)
        val nextMaxHp = profile.maxHp
        val nextMaxEnergy = profile.maxEnergy
        return player.copy(
            name = profile.name,
            hp = (nextMaxHp - hpDeficit).coerceIn(0, nextMaxHp),
            maxHp = nextMaxHp,
            energy = (nextMaxEnergy - energyDeficit).coerceIn(0, nextMaxEnergy),
            maxEnergy = nextMaxEnergy,
            profile = profile,
        )
    }
}
