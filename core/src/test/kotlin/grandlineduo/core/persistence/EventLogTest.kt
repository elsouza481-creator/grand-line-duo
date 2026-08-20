package grandlineduo.core.persistence

import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.events.EventType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files
import java.nio.file.StandardOpenOption

object EventLogTest {
    fun register() {
        test("event log round trip preserves complete ordered events") {
            val dir = Files.createTempDirectory("gld-eventlog")
            val log = EventLog(dir)
            val events = listOf(event(1), event(2), event(3))
            events.forEach(log::append)

            assertEquals(events, log.readValidPrefix())
        }

        test("partial trailing event is discarded without damaging valid prefix") {
            val dir = Files.createTempDirectory("gld-eventlog-torn")
            val log = EventLog(dir)
            val first = event(1)
            val second = event(2)
            log.append(first)
            log.append(second)
            val validLength = Files.size(log.path)
            Files.write(
                log.path,
                byteArrayOf(0, 0, 0, 120, 1, 2, 3, 4, 5),
                StandardOpenOption.APPEND,
            )
            assertTrue(Files.size(log.path) > validLength)

            assertEquals(listOf(first, second), log.readValidPrefix(truncateIncompleteTail = true))
            assertEquals(validLength, Files.size(log.path))
        }

        test("checksum failure inside committed prefix is treated as corruption") {
            val dir = Files.createTempDirectory("gld-eventlog-corrupt")
            val log = EventLog(dir)
            log.append(event(1))
            log.append(event(2))
            val bytes = Files.readAllBytes(log.path)
            bytes[20] = (bytes[20].toInt() xor 0x7f).toByte()
            Files.write(log.path, bytes)

            var failed = false
            try { log.readValidPrefix() } catch (_: EventLogCorruptionException) { failed = true }
            assertEquals(true, failed)
        }
    }

    private fun event(id: Long) = CampaignEvent(
        eventId = id,
        campaignId = "c1",
        eventType = EventType.FLAG_SET,
        actorId = "p1",
        payloadVersion = 1,
        payload = mapOf("key" to "flag-$id", "value" to "true"),
        hostTimestamp = 1000 + id,
        stateHashBefore = "before-$id",
        stateHashAfter = "after-$id",
        commandId = "cmd-$id",
    )
}
