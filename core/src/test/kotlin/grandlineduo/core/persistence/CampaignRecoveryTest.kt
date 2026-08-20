package grandlineduo.core.persistence

import grandlineduo.core.commands.AuthoritativeCommandProcessor
import grandlineduo.core.commands.GrantBerriesCommand
import grandlineduo.core.model.WorldState
import grandlineduo.test.assertEquals
import grandlineduo.test.test
import java.nio.file.Files
import java.nio.file.StandardOpenOption

object CampaignRecoveryTest {
    fun register() {
        test("restart after crash rebuilds state from snapshot plus valid event tail") {
            val dir = Files.createTempDirectory("gld-recovery")
            val initial = WorldState(campaignId = "recover-1")
            val processor = AuthoritativeCommandProcessor(initial)
            val e1 = processor.submit(GrantBerriesCommand("c1", "p1", 10), 1001).event
            val afterE1 = processor.state
            val e2 = processor.submit(GrantBerriesCommand("c2", "p2", 20), 1002).event
            val e3 = processor.submit(GrantBerriesCommand("c3", "p1", 30), 1003).event
            val expected = processor.state

            SnapshotStore(dir).save(afterE1)
            val log = EventLog(dir)
            listOf(e1, e2, e3).forEach(log::append)
            Files.write(log.path, byteArrayOf(0, 0, 0, 100, 1, 2), StandardOpenOption.APPEND)

            val recovered = CampaignRecovery(dir).recover()

            assertEquals(expected, recovered.state)
            assertEquals(listOf(e1, e2, e3), recovered.events)
        }
    }
}
