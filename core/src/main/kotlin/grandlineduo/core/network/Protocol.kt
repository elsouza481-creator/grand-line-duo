package grandlineduo.core.network

import java.security.MessageDigest

const val PROTOCOL_VERSION: Int = 5

data class ProtocolEnvelope(
    val protocolVersion: Int,
    val campaignId: String,
    val sessionId: String,
    val senderId: String,
    val sequenceNumber: Long,
    val ackNumber: Long,
    val messageType: String,
    val payload: Map<String, String>,
    val checksum: String,
) {
    fun hasValidChecksum(): Boolean = checksum == computeChecksum(
        protocolVersion,
        campaignId,
        sessionId,
        senderId,
        sequenceNumber,
        ackNumber,
        messageType,
        payload,
    )

    companion object {
        fun create(
            campaignId: String,
            sessionId: String,
            senderId: String,
            sequenceNumber: Long,
            ackNumber: Long,
            messageType: String,
            payload: Map<String, String>,
            protocolVersion: Int = PROTOCOL_VERSION,
        ): ProtocolEnvelope {
            val checksum = computeChecksum(
                protocolVersion,
                campaignId,
                sessionId,
                senderId,
                sequenceNumber,
                ackNumber,
                messageType,
                payload,
            )
            return ProtocolEnvelope(
                protocolVersion,
                campaignId,
                sessionId,
                senderId,
                sequenceNumber,
                ackNumber,
                messageType,
                payload.toSortedMap(),
                checksum,
            )
        }

        private fun computeChecksum(
            protocolVersion: Int,
            campaignId: String,
            sessionId: String,
            senderId: String,
            sequenceNumber: Long,
            ackNumber: Long,
            messageType: String,
            payload: Map<String, String>,
        ): String {
            val canonical = buildString {
                field("protocolVersion", protocolVersion.toString())
                field("campaignId", campaignId)
                field("sessionId", sessionId)
                field("senderId", senderId)
                field("sequenceNumber", sequenceNumber.toString())
                field("ackNumber", ackNumber.toString())
                field("messageType", messageType)
                append("payload=").append(payload.size).append(';')
                payload.toSortedMap().forEach { (key, value) ->
                    field("key", key)
                    field("value", value)
                }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        private fun StringBuilder.field(name: String, value: String) {
            append(name.length).append(':').append(name)
            append(value.length).append(':').append(value).append(';')
        }
    }
}

data class ReconnectHello(
    val protocolVersion: Int,
    val campaignId: String,
    val lastConfirmedEventId: Long,
    val stateHash: String,
    val peerId: String = "p2",
)

class ProtocolNegotiationException(message: String) : RuntimeException(message)
