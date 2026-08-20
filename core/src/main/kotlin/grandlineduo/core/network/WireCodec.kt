package grandlineduo.core.network

import grandlineduo.core.commands.GrantBerriesCommand
import grandlineduo.core.persistence.EventCodec
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.game.character.Attribute
import grandlineduo.game.character.CharacterDraft
import grandlineduo.game.character.Skill
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

private const val WIRE_MAGIC = 0x474C5731
private const val WIRE_MAX_PAYLOAD = 8 * 1024 * 1024

object WireCodec {
    fun encodeFrame(message: WireMessage): ByteArray {
        val payload = encodePayload(message)
        if (payload.size > WIRE_MAX_PAYLOAD) throw WireProtocolException("Wire payload too large")
        val checksum = sha256(payload)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(WIRE_MAGIC)
                data.writeInt(payload.size)
                data.write(checksum)
                data.write(payload)
            }
        }.toByteArray()
    }

    fun decodeFrame(frame: ByteArray): WireMessage = try {
        DataInputStream(ByteArrayInputStream(frame)).use { input ->
            val message = readFromDataInput(input)
            if (input.available() != 0) throw WireProtocolException("Trailing wire bytes")
            message
        }
    } catch (e: WireProtocolException) {
        throw e
    } catch (e: Exception) {
        throw WireProtocolException("Invalid wire frame: ${e.message}")
    }

    fun write(output: OutputStream, message: WireMessage) {
        val frame = encodeFrame(message)
        output.write(frame)
        output.flush()
    }

    fun read(input: InputStream): WireMessage = try {
        readFromDataInput(DataInputStream(input))
    } catch (e: WireProtocolException) {
        throw e
    } catch (e: Exception) {
        throw WireProtocolException("Invalid wire stream: ${e.message}")
    }

    private fun readFromDataInput(input: DataInputStream): WireMessage {
        if (input.readInt() != WIRE_MAGIC) throw WireProtocolException("Invalid wire magic")
        val payloadLength = input.readInt()
        if (payloadLength !in 0..WIRE_MAX_PAYLOAD) throw WireProtocolException("Invalid wire length")
        val expectedChecksum = ByteArray(32)
        input.readFully(expectedChecksum)
        val payload = ByteArray(payloadLength)
        input.readFully(payload)
        if (!MessageDigest.isEqual(expectedChecksum, sha256(payload))) {
            throw WireProtocolException("Wire checksum mismatch")
        }
        return decodePayload(payload)
    }

    private fun encodePayload(message: WireMessage): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                when (message) {
                    is WireMessage.Hello -> {
                        data.writeByte(1)
                        data.writeInt(message.hello.protocolVersion)
                        data.writeUTF(message.hello.campaignId)
                        data.writeLong(message.hello.lastConfirmedEventId)
                        data.writeUTF(message.hello.stateHash)
                        data.writeUTF(message.hello.peerId)
                    }
                    is WireMessage.Command -> {
                        data.writeByte(2)
                        data.writeUTF(message.command.commandId)
                        data.writeUTF(message.command.actorId)
                        data.writeLong(message.command.amount)
                    }
                    is WireMessage.Event -> {
                        data.writeByte(3)
                        data.writeSized(EventCodec.encode(message.event))
                    }
                    is WireMessage.Sync -> when (val plan = message.plan) {
                        SyncPlan.UpToDate -> data.writeByte(4)
                        is SyncPlan.Delta -> {
                            data.writeByte(5)
                            data.writeInt(plan.events.size)
                            plan.events.forEach { data.writeSized(EventCodec.encode(it)) }
                        }
                        is SyncPlan.FullSnapshot -> {
                            data.writeByte(6)
                            data.writeSized(WorldStateCodec.encode(plan.state))
                        }
                    }
                    is WireMessage.Error -> {
                        data.writeByte(7)
                        data.writeUTF(message.message)
                    }
                    is WireMessage.GameplayCommand -> {
                        data.writeByte(8)
                        when (val command = message.command) {
                            is GameplayWireCommand.ScenarioChoice -> {
                                data.writeByte(1)
                                data.writeUTF(command.commandId)
                                data.writeUTF(command.actorId)
                                data.writeUTF(command.choiceId)
                            }
                            is GameplayWireCommand.CombatAction -> {
                                data.writeByte(2)
                                data.writeUTF(command.commandId)
                                data.writeUTF(command.actorId)
                                data.writeUTF(command.actionType)
                            }
                            is GameplayWireCommand.CharacterCreate -> {
                                data.writeByte(3)
                                data.writeUTF(command.commandId)
                                data.writeUTF(command.actorId)
                                data.writeCharacterDraft(command.draft)
                            }
                            is GameplayWireCommand.VoyageAction -> {
                                data.writeByte(4)
                                data.writeUTF(command.commandId)
                                data.writeUTF(command.actorId)
                                data.writeUTF(command.actionType)
                            }
                            is GameplayWireCommand.ArcChoice -> {
                                data.writeByte(5)
                                data.writeUTF(command.commandId)
                                data.writeUTF(command.actorId)
                                data.writeUTF(command.choiceId)
                            }
                            is GameplayWireCommand.InventoryAction -> {
                                data.writeByte(6)
                                data.writeUTF(command.commandId)
                                data.writeUTF(command.actorId)
                                data.writeUTF(command.actionType)
                                data.writeUTF(command.target)
                                data.writeInt(command.amount)
                            }
                            is GameplayWireCommand.WorldAction -> {
                                data.writeByte(7)
                                data.writeUTF(command.commandId)
                                data.writeUTF(command.actorId)
                                data.writeUTF(command.actionType)
                                data.writeUTF(command.target)
                                data.writeInt(command.amount)
                            }
                            is GameplayWireCommand.PowerAction -> {
                                data.writeByte(8)
                                data.writeUTF(command.commandId)
                                data.writeUTF(command.actorId)
                                data.writeUTF(command.techniqueId)
                            }
                        }
                    }
                    is WireMessage.Refresh -> {
                        data.writeByte(9)
                        data.writeInt(message.hello.protocolVersion)
                        data.writeUTF(message.hello.campaignId)
                        data.writeLong(message.hello.lastConfirmedEventId)
                        data.writeUTF(message.hello.stateHash)
                        data.writeUTF(message.hello.peerId)
                    }
                }
            }
        }.toByteArray()

    private fun decodePayload(payload: ByteArray): WireMessage = try {
        DataInputStream(ByteArrayInputStream(payload)).use { data ->
            val message = when (val type = data.readUnsignedByte()) {
                1 -> WireMessage.Hello(
                    ReconnectHello(
                        protocolVersion = data.readInt(),
                        campaignId = data.readUTF(),
                        lastConfirmedEventId = data.readLong(),
                        stateHash = data.readUTF(),
                        peerId = data.readUTF(),
                    )
                )
                2 -> WireMessage.Command(
                    GrantBerriesCommand(
                        commandId = data.readUTF(),
                        actorId = data.readUTF(),
                        amount = data.readLong(),
                    )
                )
                3 -> WireMessage.Event(EventCodec.decode(data.readSized()))
                4 -> WireMessage.Sync(SyncPlan.UpToDate)
                5 -> {
                    val count = data.readInt()
                    if (count !in 0..100_000) throw WireProtocolException("Invalid delta count")
                    val events = List(count) { EventCodec.decode(data.readSized()) }
                    WireMessage.Sync(SyncPlan.Delta(events))
                }
                6 -> WireMessage.Sync(SyncPlan.FullSnapshot(WorldStateCodec.decode(data.readSized())))
                7 -> WireMessage.Error(data.readUTF())
                8 -> {
                    val command = when (val subtype = data.readUnsignedByte()) {
                        1 -> GameplayWireCommand.ScenarioChoice(
                            commandId = data.readUTF(),
                            actorId = data.readUTF(),
                            choiceId = data.readUTF(),
                        )
                        2 -> GameplayWireCommand.CombatAction(
                            commandId = data.readUTF(),
                            actorId = data.readUTF(),
                            actionType = data.readUTF(),
                        )
                        3 -> GameplayWireCommand.CharacterCreate(
                            commandId = data.readUTF(),
                            actorId = data.readUTF(),
                            draft = data.readCharacterDraft(),
                        )
                        4 -> GameplayWireCommand.VoyageAction(
                            commandId = data.readUTF(),
                            actorId = data.readUTF(),
                            actionType = data.readUTF(),
                        )
                        5 -> GameplayWireCommand.ArcChoice(
                            commandId = data.readUTF(),
                            actorId = data.readUTF(),
                            choiceId = data.readUTF(),
                        )
                        6 -> GameplayWireCommand.InventoryAction(
                            commandId = data.readUTF(),
                            actorId = data.readUTF(),
                            actionType = data.readUTF(),
                            target = data.readUTF(),
                            amount = data.readInt(),
                        )
                        7 -> GameplayWireCommand.WorldAction(
                            commandId = data.readUTF(),
                            actorId = data.readUTF(),
                            actionType = data.readUTF(),
                            target = data.readUTF(),
                            amount = data.readInt(),
                        )
                        8 -> GameplayWireCommand.PowerAction(
                            commandId = data.readUTF(),
                            actorId = data.readUTF(),
                            techniqueId = data.readUTF(),
                        )
                        else -> throw WireProtocolException("Unknown gameplay command type $subtype")
                    }
                    WireMessage.GameplayCommand(command)
                }
                9 -> WireMessage.Refresh(
                    ReconnectHello(
                        protocolVersion = data.readInt(),
                        campaignId = data.readUTF(),
                        lastConfirmedEventId = data.readLong(),
                        stateHash = data.readUTF(),
                        peerId = data.readUTF(),
                    )
                )
                else -> throw WireProtocolException("Unknown wire message type $type")
            }
            if (data.available() != 0) throw WireProtocolException("Trailing payload bytes")
            message
        }
    } catch (e: WireProtocolException) {
        throw e
    } catch (e: Exception) {
        throw WireProtocolException("Invalid wire payload: ${e.message}")
    }


    private fun DataOutputStream.writeCharacterDraft(draft: CharacterDraft) {
        writeUTF(draft.name)
        writeInt(draft.age)
        writeUTF(draft.origin)
        writeUTF(draft.appearance)
        writeUTF(draft.personality)
        writeUTF(draft.dream)
        writeUTF(draft.fear)
        writeUTF(draft.profession)
        writeUTF(draft.combatStyle)
        writeUTF(draft.background)
        writeUTF(draft.motivation)
        writeUTF(draft.pirateRelation)
        writeUTF(draft.marineRelation)
        writeUTF(draft.importantPerson)
        writeUTF(draft.defect)

        val attributes = draft.attributes.entries.sortedBy { it.key.ordinal }
        writeInt(attributes.size)
        attributes.forEach { (attribute, value) ->
            writeUTF(attribute.name)
            writeInt(value)
        }

        val skills = draft.skills.entries.sortedBy { it.key.ordinal }
        writeInt(skills.size)
        skills.forEach { (skill, value) ->
            writeUTF(skill.name)
            writeInt(value)
        }
    }

    private fun DataInputStream.readCharacterDraft(): CharacterDraft {
        val name = readUTF()
        val age = readInt()
        val origin = readUTF()
        val appearance = readUTF()
        val personality = readUTF()
        val dream = readUTF()
        val fear = readUTF()
        val profession = readUTF()
        val combatStyle = readUTF()
        val background = readUTF()
        val motivation = readUTF()
        val pirateRelation = readUTF()
        val marineRelation = readUTF()
        val importantPerson = readUTF()
        val defect = readUTF()

        val attributeCount = readInt()
        if (attributeCount !in 0..Attribute.entries.size) throw WireProtocolException("Invalid character attribute count")
        val attributes = linkedMapOf<Attribute, Int>()
        repeat(attributeCount) {
            val attribute = Attribute.valueOf(readUTF())
            if (attribute in attributes) throw WireProtocolException("Duplicate character attribute")
            attributes[attribute] = readInt()
        }

        val skillCount = readInt()
        if (skillCount !in 0..Skill.entries.size) throw WireProtocolException("Invalid character skill count")
        val skills = linkedMapOf<Skill, Int>()
        repeat(skillCount) {
            val skill = Skill.valueOf(readUTF())
            if (skill in skills) throw WireProtocolException("Duplicate character skill")
            skills[skill] = readInt()
        }

        return CharacterDraft(
            name = name,
            age = age,
            origin = origin,
            appearance = appearance,
            personality = personality,
            dream = dream,
            fear = fear,
            profession = profession,
            combatStyle = combatStyle,
            background = background,
            motivation = motivation,
            pirateRelation = pirateRelation,
            marineRelation = marineRelation,
            importantPerson = importantPerson,
            defect = defect,
            attributes = attributes,
            skills = skills,
        )
    }

    private fun DataOutputStream.writeSized(bytes: ByteArray) {
        if (bytes.size > WIRE_MAX_PAYLOAD) throw WireProtocolException("Embedded payload too large")
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInput.readSized(): ByteArray {
        val size = readInt()
        if (size !in 0..WIRE_MAX_PAYLOAD) throw WireProtocolException("Invalid embedded payload length")
        return ByteArray(size).also { readFully(it) }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
