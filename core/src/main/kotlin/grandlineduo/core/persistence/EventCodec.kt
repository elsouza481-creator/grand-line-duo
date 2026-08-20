package grandlineduo.core.persistence

import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.events.EventType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object EventCodec {
    fun encode(event: CampaignEvent): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(1)
            data.writeLong(event.eventId)
            data.writeUTF(event.campaignId)
            data.writeUTF(event.eventType.name)
            data.writeUTF(event.actorId)
            data.writeInt(event.payloadVersion)
            val payload = event.payload.toSortedMap()
            data.writeInt(payload.size)
            payload.forEach { (key, value) ->
                data.writeUTF(key)
                data.writeUTF(value)
            }
            data.writeLong(event.hostTimestamp)
            data.writeUTF(event.stateHashBefore)
            data.writeUTF(event.stateHashAfter)
            data.writeUTF(event.commandId)
            data.writeUTF(event.commandFingerprint)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): CampaignEvent {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val version = data.readInt()
            require(version == 1) { "Unsupported event version: $version" }
            val eventId = data.readLong()
            val campaignId = data.readUTF()
            val eventType = EventType.valueOf(data.readUTF())
            val actorId = data.readUTF()
            val payloadVersion = data.readInt()
            val payloadCount = data.readInt()
            require(payloadCount in 0..10_000) { "Invalid payload size" }
            val payload = linkedMapOf<String, String>()
            repeat(payloadCount) { payload[data.readUTF()] = data.readUTF() }
            val hostTimestamp = data.readLong()
            val before = data.readUTF()
            val after = data.readUTF()
            val commandId = data.readUTF()
            val commandFingerprint = data.readUTF()
            require(data.available() == 0) { "Trailing event bytes" }
            return CampaignEvent(
                eventId = eventId,
                campaignId = campaignId,
                eventType = eventType,
                actorId = actorId,
                payloadVersion = payloadVersion,
                payload = payload,
                hostTimestamp = hostTimestamp,
                stateHashBefore = before,
                stateHashAfter = after,
                commandId = commandId,
                commandFingerprint = commandFingerprint,
            )
        }
    }
}
