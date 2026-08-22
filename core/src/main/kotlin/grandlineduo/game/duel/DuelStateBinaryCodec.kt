package grandlineduo.game.duel

import grandlineduo.game.combat.CombatAction
import grandlineduo.game.combat.CombatActionType
import java.io.DataInputStream
import java.io.DataOutputStream

object DuelStateBinaryCodec {
    fun write(data: DataOutputStream, duel: DuelState) {
        validate(duel)
        data.writeUTF(duel.duelId)
        data.writeUTF(duel.challengerId)
        data.writeUTF(duel.challengedId)
        data.writeUTF(duel.phase.name)
        data.writeInt(duel.round)

        val fighters = duel.fighters.toSortedMap()
        data.writeInt(fighters.size)
        fighters.forEach { (playerId, fighter) ->
            data.writeUTF(playerId)
            data.writeUTF(fighter.id)
            data.writeUTF(fighter.name)
            data.writeInt(fighter.hp)
            data.writeInt(fighter.maxHp)
        }

        val actions = duel.lockedActions.toSortedMap()
        data.writeInt(actions.size)
        actions.forEach { (playerId, action) ->
            data.writeUTF(playerId)
            data.writeUTF(action.playerId)
            data.writeUTF(action.type.name)
        }

        val setup = duel.setupReady.sorted()
        data.writeInt(setup.size)
        setup.forEach(data::writeUTF)

        writeNullableString(data, duel.winnerId)
        writeNullableString(data, duel.loserId)
        writeNullableString(data, duel.finishReason?.name)
    }

    fun read(data: DataInputStream): DuelState {
        val duelId = data.readUTF()
        val challengerId = data.readUTF()
        val challengedId = data.readUTF()
        val phase = DuelPhase.valueOf(data.readUTF())
        val round = data.readInt()

        val fighterCount = data.readInt()
        require(fighterCount in 0..2) { "Invalid duel fighter count" }
        val fighters = linkedMapOf<String, DuelFighter>()
        repeat(fighterCount) {
            val key = data.readUTF()
            require(key !in fighters) { "Duplicate duel fighter $key" }
            fighters[key] = DuelFighter(
                id = data.readUTF(),
                name = data.readUTF(),
                hp = data.readInt(),
                maxHp = data.readInt(),
            )
        }

        val actionCount = data.readInt()
        require(actionCount in 0..2) { "Invalid duel action count" }
        val actions = linkedMapOf<String, CombatAction>()
        repeat(actionCount) {
            val key = data.readUTF()
            require(key !in actions) { "Duplicate duel action $key" }
            actions[key] = CombatAction(
                playerId = data.readUTF(),
                type = CombatActionType.valueOf(data.readUTF()),
            )
        }

        val setupCount = data.readInt()
        require(setupCount in 0..2) { "Invalid duel setup count" }
        val setup = linkedSetOf<String>()
        repeat(setupCount) {
            val playerId = data.readUTF()
            require(setup.add(playerId)) { "Duplicate duel setup player $playerId" }
        }

        val duel = DuelState(
            duelId = duelId,
            challengerId = challengerId,
            challengedId = challengedId,
            phase = phase,
            round = round,
            fighters = fighters,
            lockedActions = actions,
            setupReady = setup,
            winnerId = readNullableString(data),
            loserId = readNullableString(data),
            finishReason = readNullableString(data)?.let(DuelFinishReason::valueOf),
        )
        validate(duel)
        return duel
    }

    private fun validate(duel: DuelState) {
        require(duel.duelId.isNotBlank()) { "Duel id is required" }
        require(duel.challengerId in PARTICIPANTS) { "Invalid duel challenger" }
        require(duel.challengedId in PARTICIPANTS) { "Invalid duel challenged player" }
        require(duel.challengerId != duel.challengedId) { "Duel participants must be distinct" }
        require(duel.round >= 0) { "Invalid duel round" }
        require(duel.fighters.size <= 2) { "Invalid duel fighter count" }
        require(duel.lockedActions.size <= 2) { "Invalid duel action count" }
        require(duel.setupReady.size <= 2) { "Invalid duel setup count" }

        duel.fighters.forEach { (key, fighter) ->
            require(key in PARTICIPANTS && fighter.id == key) { "Duel fighter id mismatch" }
            require(fighter.maxHp > 0 && fighter.hp in 0..fighter.maxHp) { "Invalid duel fighter hp" }
        }
        duel.lockedActions.forEach { (key, action) ->
            require(key in PARTICIPANTS && action.playerId == key) { "Duel action player mismatch" }
            require(key in duel.fighters) { "Duel action belongs to missing fighter" }
        }
        require(duel.setupReady.all { it in PARTICIPANTS && it in duel.fighters }) { "Invalid duel setup player" }

        when (duel.phase) {
            DuelPhase.PENDING -> {
                require(duel.round == 0) { "Pending duel must be round zero" }
                require(duel.fighters.isEmpty()) { "Pending duel cannot have fighters" }
                require(duel.lockedActions.isEmpty()) { "Pending duel cannot have actions" }
                require(duel.setupReady.isEmpty()) { "Pending duel cannot have setup state" }
                require(duel.winnerId == null && duel.loserId == null && duel.finishReason == null) {
                    "Pending duel cannot have terminal state"
                }
            }
            DuelPhase.ACTIVE -> {
                require(duel.round >= 1) { "Active duel requires a positive round" }
                require(duel.fighters.keys == PARTICIPANTS) { "Active duel requires both fighters" }
                require(duel.fighters.values.all { it.hp > 0 }) { "Active duel fighter must be standing" }
                require(duel.winnerId == null && duel.loserId == null && duel.finishReason == null) {
                    "Active duel cannot have terminal state"
                }
            }
            DuelPhase.FINISHED -> {
                require(duel.round >= 1) { "Finished duel requires a positive round" }
                require(duel.fighters.keys == PARTICIPANTS) { "Finished duel requires both fighters" }
                require(duel.lockedActions.isEmpty()) { "Finished duel cannot have locked actions" }
                require(duel.setupReady.isEmpty()) { "Finished duel cannot have setup state" }
                when (duel.finishReason) {
                    DuelFinishReason.KNOCKOUT -> {
                        require(duel.winnerId in PARTICIPANTS && duel.loserId in PARTICIPANTS) {
                            "Knockout requires winner and loser"
                        }
                        require(duel.winnerId != duel.loserId) { "Winner and loser must differ" }
                        require(duel.fighters.getValue(duel.loserId!!).hp == 1) { "Knockout loser must remain at one hp" }
                    }
                    DuelFinishReason.DOUBLE_KNOCKOUT -> {
                        require(duel.winnerId == null && duel.loserId == null) { "Double knockout has no winner" }
                        require(duel.fighters.values.all { it.hp == 1 }) { "Double knockout fighters remain at one hp" }
                    }
                    null -> error("Finished duel requires a finish reason")
                }
            }
        }
    }

    private fun writeNullableString(data: DataOutputStream, value: String?) {
        data.writeBoolean(value != null)
        value?.let(data::writeUTF)
    }

    private fun readNullableString(data: DataInputStream): String? =
        if (data.readBoolean()) data.readUTF() else null

    private val PARTICIPANTS = setOf("p1", "p2")
}
