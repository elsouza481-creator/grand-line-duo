package grandlineduo.core.commands

import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object CommandIdempotencyTest {
    fun register() {
        test("retrying the same command id returns the original event without applying twice") {
            val processor = AuthoritativeCommandProcessor(WorldState(campaignId = "c1", partyBerries = 100))
            val command = GrantBerriesCommand(
                commandId = "cmd-loot-1",
                actorId = "p1",
                amount = 75,
            )

            val first = processor.submit(command, hostTimestamp = 1000)
            val retry = processor.submit(command, hostTimestamp = 9999)

            assertEquals(175L, processor.state.partyBerries)
            assertEquals(1L, processor.state.lastEventId)
            assertEquals(first.event, retry.event)
            assertEquals(true, retry.wasDuplicate)
        }

        test("reusing a command id with different content is rejected") {
            val processor = AuthoritativeCommandProcessor(WorldState(campaignId = "c1"))
            processor.submit(GrantBerriesCommand("same-id", "p1", 10), 1000)

            var failed = false
            try {
                processor.submit(GrantBerriesCommand("same-id", "p1", 999), 1001)
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertEquals(true, failed)
            assertEquals(10L, processor.state.partyBerries)
        }
    }
}
