package grandlineduo.game.character

import grandlineduo.core.model.WorldState

object ClassTrainingRules {
    const val ENERGY_COST = 5
    const val EXPERIENCE_GAIN = 25L

    fun train(world: WorldState, playerId: String, path: ClassPath): WorldState {
        val player = world.players[playerId]
            ?: throw IllegalArgumentException("Unknown player $playerId")
        require(player.energy >= ENERGY_COST) {
            "Class training requires $ENERGY_COST energy"
        }
        val profile = player.profile
            ?: throw IllegalArgumentException("Character not created for $playerId")
        val mastery = profile.classMastery
            ?: throw IllegalArgumentException("Primary class must be chosen before class training")
        val trainedProfile = profile.copy(
            classMastery = ClassMasteryEngine.train(mastery, path, EXPERIENCE_GAIN),
        )
        val trainedPlayer = CharacterStateSync.applyProfile(player, trainedProfile).copy(
            energy = player.energy - ENERGY_COST,
        )
        return world.copy(players = world.players + (playerId to trainedPlayer))
    }
}
