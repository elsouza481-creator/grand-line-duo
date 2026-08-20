package grandlineduo.core.persistence

import grandlineduo.core.events.CampaignEvent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

private const val EVENT_FRAME_MAGIC = 0x474C4531
private const val FRAME_HEADER_SIZE = 4 + 4 + 32
private const val MAX_EVENT_BYTES = 4 * 1024 * 1024

class EventLogCorruptionException(message: String) : RuntimeException(message)

class EventLog(private val directory: Path) {
    val path: Path = directory.resolve("campaign.events")

    fun append(event: CampaignEvent) {
        Files.createDirectories(directory)
        val payload = EventCodec.encode(event)
        require(payload.size <= MAX_EVENT_BYTES) { "Event too large" }
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        val frame = ByteArrayOutputStream()
        DataOutputStream(frame).use { data ->
            data.writeInt(EVENT_FRAME_MAGIC)
            data.writeInt(payload.size)
            data.write(checksum)
            data.write(payload)
        }
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(frame.toByteArray())
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    fun readValidPrefix(truncateIncompleteTail: Boolean = false): List<CampaignEvent> {
        if (!Files.exists(path)) return emptyList()
        val bytes = Files.readAllBytes(path)
        val events = mutableListOf<CampaignEvent>()
        var offset = 0
        var lastGoodOffset = 0

        while (offset < bytes.size) {
            val remaining = bytes.size - offset
            if (remaining < FRAME_HEADER_SIZE) {
                if (truncateIncompleteTail) truncateTo(lastGoodOffset)
                return events
            }

            val header = ByteBuffer.wrap(bytes, offset, FRAME_HEADER_SIZE)
            val magic = header.int
            if (magic != EVENT_FRAME_MAGIC) {
                throw EventLogCorruptionException("Invalid frame magic at offset $offset")
            }
            val payloadLength = header.int
            if (payloadLength < 0 || payloadLength > MAX_EVENT_BYTES) {
                throw EventLogCorruptionException("Invalid event length at offset $offset")
            }
            val checksum = ByteArray(32)
            header.get(checksum)
            val frameLength = FRAME_HEADER_SIZE + payloadLength
            if (remaining < frameLength) {
                if (truncateIncompleteTail) truncateTo(lastGoodOffset)
                return events
            }

            val payloadStart = offset + FRAME_HEADER_SIZE
            val payload = bytes.copyOfRange(payloadStart, payloadStart + payloadLength)
            val actual = MessageDigest.getInstance("SHA-256").digest(payload)
            if (!MessageDigest.isEqual(checksum, actual)) {
                throw EventLogCorruptionException("Checksum mismatch at offset $offset")
            }

            val event = try {
                EventCodec.decode(payload)
            } catch (e: Exception) {
                throw EventLogCorruptionException("Invalid event payload at offset $offset: ${e.message}")
            }
            events += event
            offset += frameLength
            lastGoodOffset = offset
        }
        return events
    }

    private fun truncateTo(length: Int) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
            channel.truncate(length.toLong())
            channel.force(true)
        }
    }
}
