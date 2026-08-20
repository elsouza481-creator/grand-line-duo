package grandlineduo.game

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

object VerticalSliceIntegrationTest {
    fun register() {
        test("Stormglass Cay vertical slice completes with private discovery GM reaction combat autosave and reward") {
            val dir = Files.createTempDirectory("gld-vertical-slice")
            val result = StormglassVerticalSlice.run(seed = 20260818, saveDirectory = dir)

            assertEquals(ScenarioStage.COMPLETE, result.scenario.stage)
            assertEquals(CombatStatus.VICTORY, result.finalCombatStatus)
            assertTrue("marine_manifest" in result.privateKnowledgeP2)
            assertTrue("manifest_revealed" in result.scenario.sharedFlags)
            assertEquals("security-lockdown", result.directorDecision.event.id)
            assertTrue(result.coopCombos >= 1)
            assertTrue("log_pose_recovered" in result.scenario.sharedFlags)
            assertEquals(250_000L, result.world.partyBerries)
            assertTrue(result.world.players.getValue("p1").bounty > 0)
            assertTrue(result.world.players.getValue("p2").bounty > 0)

            val saved = SnapshotStore(dir).loadLatestValid()!!
            assertEquals(
                CanonicalStateHasher.hash(result.world),
                CanonicalStateHasher.hash(saved),
            )
            assertTrue(result.transcript.any { "CO-OP" in it })
            assertTrue(result.transcript.any { "Director" in it })
        }
    }
}
