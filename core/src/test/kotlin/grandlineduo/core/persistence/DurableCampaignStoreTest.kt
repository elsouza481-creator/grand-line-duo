package grandlineduo.core.persistence

import grandlineduo.core.commands.AuthoritativeCommandProcessor
import grandlineduo.core.commands.GrantBerriesCommand
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object DurableCampaignStoreTest {
    fun register() {
        test("crash after durable event append but before snapshot recovers accepted event") {
            val dir = Files.createTempDirectory("gld-durable-crash")
            val initial = WorldState(campaignId = "durable-1")
            val processor = AuthoritativeCommandProcessor(initial)
            val event = processor.submit(GrantBerriesCommand("loot-1", "p1", 75), 1000).event
            val expected = processor.state
            var crashOnce = true
            val store = DurableCampaignStore(dir, DurableCommitFaultInjector {
                if (crashOnce) {
                    crashOnce = false
                    throw SimulatedDurableCommitCrash()
                }
            })
            store.initialize(initial)

            var failed = false
            try { store.commit(event, expected) } catch (_: SimulatedDurableCommitCrash) { failed = true }
            assertTrue(failed)

            val recovered = DurableCampaignStore(dir).recover()
            assertEquals(expected, recovered.state)
            assertEquals(listOf(event), recovered.events)
        }

        test("retrying commit after crash does not duplicate the event log") {
            val dir = Files.createTempDirectory("gld-durable-retry")
            val initial = WorldState(campaignId = "durable-2")
            val processor = AuthoritativeCommandProcessor(initial)
            val event = processor.submit(GrantBerriesCommand("loot-1", "p1", 25), 1000).event
            val expected = processor.state
            var crashOnce = true
            val crashing = DurableCampaignStore(dir, DurableCommitFaultInjector {
                if (crashOnce) {
                    crashOnce = false
                    throw SimulatedDurableCommitCrash()
                }
            })
            crashing.initialize(initial)
            try { crashing.commit(event, expected) } catch (_: SimulatedDurableCommitCrash) { }

            val restarted = DurableCampaignStore(dir)
            restarted.commit(event, expected)

            assertEquals(listOf(event), EventLog(dir).readValidPrefix())
            assertEquals(expected, restarted.recover().state)
        }
    }
}
