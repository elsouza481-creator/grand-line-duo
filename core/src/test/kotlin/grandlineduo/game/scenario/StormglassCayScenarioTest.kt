package grandlineduo.game.scenario

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object StormglassCayScenarioTest {
    fun register() {
        test("arrival presents different contextual choices to P1 and P2") {
            val scenario = StormglassCayScenario()
            val state = scenario.initialState()

            assertEquals(
                setOf("help_dockworker", "visit_tavern"),
                scenario.view(state, "p1").choices.map { it.id }.toSet(),
            )
            assertEquals(
                setOf("shadow_courier", "inspect_market"),
                scenario.view(state, "p2").choices.map { it.id }.toSet(),
            )
        }

        test("invalid or other-player choice is rejected") {
            val scenario = StormglassCayScenario()
            var failed = false
            try { scenario.choose(scenario.initialState(), "p1", "shadow_courier") }
            catch (_: ScenarioChoiceException) { failed = true }
            assertEquals(true, failed)
        }

        test("P2 can discover a Marine manifest privately without revealing it to P1") {
            val scenario = StormglassCayScenario()
            val outcome = scenario.choose(scenario.initialState(), "p2", "shadow_courier")

            assertTrue("marine_manifest" in outcome.state.privateKnowledge["p2"].orEmpty())
            assertTrue("marine_manifest" !in outcome.state.privateKnowledge["p1"].orEmpty())
            assertTrue(outcome.beatsFor("p2").any { "manifesto" in it.text.lowercase() })
            assertEquals(emptyList<NarrativeBeat>(), outcome.beatsFor("p1"))
        }

        test("saving the dockworker creates a shared consequence") {
            val scenario = StormglassCayScenario()
            val outcome = scenario.choose(scenario.initialState(), "p1", "help_dockworker")
            assertTrue("dockworker_saved" in outcome.state.sharedFlags)
            assertTrue(outcome.beatsFor("p1").isNotEmpty())
            assertTrue(outcome.beatsFor("p2").isNotEmpty())
        }

        test("both arrival decisions advance investigation and P2 may reveal secret into shared memory") {
            val scenario = StormglassCayScenario()
            var state = scenario.initialState()
            state = scenario.choose(state, "p1", "help_dockworker").state
            state = scenario.choose(state, "p2", "shadow_courier").state
            assertEquals(ScenarioStage.INVESTIGATION, state.stage)

            val revealed = scenario.choose(state, "p2", "reveal_manifest")
            assertTrue("manifest_revealed" in revealed.state.sharedFlags)
        }
    }
}
