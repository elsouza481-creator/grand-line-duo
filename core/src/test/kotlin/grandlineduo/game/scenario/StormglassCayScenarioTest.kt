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

        test("four player Stormglass opening waits for every participant and shares public beats with all four") {
            val scenario = StormglassCayScenario()
            val participants = setOf("p1", "p2", "p3", "p4")
            var state = scenario.initialState(participants)

            assertEquals(participants, state.participantIds)
            assertTrue(scenario.view(state, "p3").choices.isNotEmpty())
            assertTrue(scenario.view(state, "p4").choices.isNotEmpty())

            val first = scenario.choose(state, "p1", "help_dockworker")
            participants.forEach { playerId ->
                assertTrue(first.beatsFor(playerId).isNotEmpty())
            }
            state = first.state
            state = scenario.choose(state, "p2", "shadow_courier").state
            state = scenario.choose(state, "p3", "visit_tavern").state
            assertEquals(ScenarioStage.ARRIVAL, state.stage)
            assertEquals(setOf("p1", "p2", "p3"), state.actedThisStage)

            state = scenario.choose(state, "p4", "inspect_market").state
            assertEquals(ScenarioStage.INVESTIGATION, state.stage)
            assertEquals(emptySet<String>(), state.actedThisStage)
            assertTrue("marine_manifest" in state.privateKnowledge["p2"].orEmpty())
            assertTrue("marine_manifest" !in state.privateKnowledge["p3"].orEmpty())
        }
    }
}
