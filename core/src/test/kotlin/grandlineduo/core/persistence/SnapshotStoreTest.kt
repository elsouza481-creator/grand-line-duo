package grandlineduo.core.persistence

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.test
import java.nio.file.Files

object SnapshotStoreTest {
    fun register() {
        test("snapshot round trip preserves the complete world state") {
            val dir = Files.createTempDirectory("gld-snapshot-roundtrip")
            val store = SnapshotStore(dir)
            val state = sampleState(eventId = 12, berries = 9876)

            store.save(state)

            assertEquals(state, store.loadLatestValid())
        }

        test("interruption before atomic replace preserves the previous snapshot") {
            val dir = Files.createTempDirectory("gld-snapshot-interrupt")
            val stable = sampleState(eventId = 5, berries = 500)
            SnapshotStore(dir).save(stable)
            val newer = sampleState(eventId = 6, berries = 900)
            val interruptedStore = SnapshotStore(dir, SnapshotFaultInjector { stage ->
                if (stage == SnapshotStage.AFTER_TEMP_VALIDATED) throw SimulatedSnapshotCrash()
            })

            try { interruptedStore.save(newer) } catch (_: SimulatedSnapshotCrash) { }

            assertEquals(stable, SnapshotStore(dir).loadLatestValid())
        }

        test("corrupted primary falls back to previous valid snapshot") {
            val dir = Files.createTempDirectory("gld-snapshot-fallback")
            val old = sampleState(eventId = 1, berries = 100)
            val newer = sampleState(eventId = 2, berries = 200)
            val store = SnapshotStore(dir)
            store.save(old)
            store.save(newer)
            Files.write(store.primaryPath, byteArrayOf(1, 2, 3, 4, 5))

            assertEquals(old, SnapshotStore(dir).loadLatestValid())
        }

        test("snapshot with invalid checksum is rejected") {
            val dir = Files.createTempDirectory("gld-snapshot-checksum")
            val store = SnapshotStore(dir)
            store.save(sampleState(eventId = 1, berries = 100))
            val bytes = Files.readAllBytes(store.primaryPath)
            bytes[bytes.lastIndex] = (bytes.last() xor 0x01)
            Files.write(store.primaryPath, bytes)

            assertEquals(null, SnapshotStore(dir).loadLatestValid())
        }
    }

    private fun sampleState(eventId: Long, berries: Long) = WorldState(
        campaignId = "campaign-save",
        lastEventId = eventId,
        islandId = "shells-town",
        partyBerries = berries,
        players = mapOf(
            "p1" to PlayerState("p1", "Kairo", 17, 20, 12345, 8, 10),
            "p2" to PlayerState("p2", "Namiya", 15, 18, 5432, 7, 12),
        ),
        worldFlags = mapOf("marine_alert" to "2", "saved_dockworker" to "true"),
    )

    private infix fun Byte.xor(value: Int): Byte = (toInt() xor value).toByte()
}
