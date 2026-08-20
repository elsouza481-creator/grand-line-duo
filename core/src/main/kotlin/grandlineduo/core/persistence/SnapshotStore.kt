package grandlineduo.core.persistence

import grandlineduo.core.model.WorldState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

private const val SNAPSHOT_MAGIC = 0x474C4431

enum class SnapshotStage {
    AFTER_TEMP_WRITTEN,
    AFTER_TEMP_VALIDATED,
    AFTER_PREVIOUS_ROTATED,
}

fun interface SnapshotFaultInjector {
    fun onStage(stage: SnapshotStage)
}

class SimulatedSnapshotCrash : RuntimeException("Simulated snapshot crash")

class SnapshotStore(
    private val directory: Path,
    private val faultInjector: SnapshotFaultInjector = SnapshotFaultInjector { },
) {
    val primaryPath: Path = directory.resolve("campaign.snapshot")
    val previousPath: Path = directory.resolve("campaign.snapshot.prev")
    private val tempPath: Path = directory.resolve("campaign.snapshot.tmp")

    fun save(state: WorldState) {
        Files.createDirectories(directory)
        val snapshotBytes = encodeSnapshot(state)
        writeAndSync(tempPath, snapshotBytes)
        faultInjector.onStage(SnapshotStage.AFTER_TEMP_WRITTEN)

        require(readSnapshot(tempPath) == state) { "Temporary snapshot validation failed" }
        faultInjector.onStage(SnapshotStage.AFTER_TEMP_VALIDATED)

        if (Files.exists(primaryPath)) {
            moveReplace(primaryPath, previousPath)
        }
        faultInjector.onStage(SnapshotStage.AFTER_PREVIOUS_ROTATED)
        moveReplace(tempPath, primaryPath)
        syncDirectoryBestEffort(directory)
    }

    fun loadLatestValid(): WorldState? =
        readSnapshot(primaryPath) ?: readSnapshot(previousPath)

    private fun encodeSnapshot(state: WorldState): ByteArray {
        val payload = WorldStateCodec.encode(state)
        val checksum = sha256(payload)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(SNAPSHOT_MAGIC)
            data.writeInt(1)
            data.writeInt(checksum.size)
            data.write(checksum)
            data.writeInt(payload.size)
            data.write(payload)
        }
        return out.toByteArray()
    }

    private fun readSnapshot(path: Path): WorldState? {
        if (!Files.exists(path)) return null
        return try {
            val bytes = Files.readAllBytes(path)
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readInt() != SNAPSHOT_MAGIC) return null
                if (data.readInt() != 1) return null
                val checksumLength = data.readInt()
                if (checksumLength != 32) return null
                val checksum = ByteArray(checksumLength)
                data.readFully(checksum)
                val payloadLength = data.readInt()
                if (payloadLength < 0 || payloadLength > bytes.size) return null
                val payload = ByteArray(payloadLength)
                data.readFully(payload)
                if (data.available() != 0) return null
                if (!MessageDigest.isEqual(checksum, sha256(payload))) return null
                WorldStateCodec.decode(payload)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAndSync(path: Path, bytes: ByteArray) {
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(bytes))
            channel.force(true)
        }
    }

    private fun moveReplace(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun syncDirectoryBestEffort(path: Path) {
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Not every filesystem allows opening a directory as a channel.
        }
    }
}
