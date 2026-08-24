package grandlineduo.game.duel

object DuelCanonicalState {
    fun encode(duel: DuelState): String = buildString {
        field("duelVersion", "1")
        field("duelId", duel.duelId)
        field("duelChallenger", duel.challengerId)
        field("duelChallenged", duel.challengedId)
        field("duelPhase", duel.phase.name)
        field("duelRound", duel.round.toString())

        append("duelFighters=").append(duel.fighters.size).append(';')
        duel.fighters.toSortedMap().forEach { (playerId, fighter) ->
            field("duelFighterKey", playerId)
            field("duelFighterId", fighter.id)
            field("duelFighterName", fighter.name)
            field("duelFighterHp", fighter.hp.toString())
            field("duelFighterMaxHp", fighter.maxHp.toString())
        }

        append("duelActions=").append(duel.lockedActions.size).append(';')
        duel.lockedActions.toSortedMap().forEach { (playerId, action) ->
            field("duelActionKey", playerId)
            field("duelActionPlayer", action.playerId)
            field("duelActionType", action.type.name)
        }

        append("duelSetup=").append(duel.setupReady.size).append(';')
        duel.setupReady.sorted().forEach { field("duelSetupPlayer", it) }

        field("duelWinner", duel.winnerId ?: "")
        field("duelLoser", duel.loserId ?: "")
        field("duelFinishReason", duel.finishReason?.name ?: "")
    }

    private fun StringBuilder.field(name: String, value: String) {
        append(name.length).append(':').append(name)
        append(value.length).append(':').append(value).append(';')
    }
}
