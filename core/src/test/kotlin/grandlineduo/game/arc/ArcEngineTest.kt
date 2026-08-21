package grandlineduo.game.arc

import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ArcEngineTest {
    fun register() {
        test("same island context and seed starts the same deterministic arc") {
            val context = ArcStartContext(
                seed = 991L,
                islandId = "ironwake-atoll",
                presentFactions = setOf("MARINES", "UNDERWORLD"),
                worldFlags = setOf("MARINE_RESPONSE_CAPTAIN"),
                totalBounty = 18_000_000L,
            )
            assertEquals(ArcEngine.start(context), ArcEngine.start(context))
        }

        test("P1 and P2 receive different contextual arrival choices") {
            val state = ArcEngine.start(marineContext())
            val p1 = ArcEngine.view(state, "p1")
            val p2 = ArcEngine.view(state, "p2")
            assertEquals(ArcPhase.ARRIVAL, p1.phase)
            assertEquals(ArcPhase.ARRIVAL, p2.phase)
            assertNotEquals(p1.choices.map { it.id }, p2.choices.map { it.id })
            assertTrue(p1.choices.isNotEmpty())
            assertTrue(p2.choices.isNotEmpty())
        }

        test("private clue discovered by P2 remains invisible to P1") {
            val state = ArcEngine.start(marineContext())
            val outcome = ArcEngine.choose(state, "p2", "shadow_authority")
            assertTrue(outcome.state.privateClues["p2"].orEmpty().isNotEmpty())
            assertTrue(outcome.state.privateClues["p1"].orEmpty().isEmpty())
            assertTrue(outcome.beatsFor("p2").any { "ordens" in it.text.lowercase() || "manifesto" in it.text.lowercase() })
            assertTrue(outcome.beatsFor("p1").isEmpty())
        }

        test("arc phase advances only after both players act") {
            val start = ArcEngine.start(marineContext())
            val afterP1 = ArcEngine.choose(start, "p1", "help_locals").state
            assertEquals(ArcPhase.ARRIVAL, afterP1.phase)
            val afterP2 = ArcEngine.choose(afterP1, "p2", "survey_route").state
            assertEquals(ArcPhase.INVESTIGATION, afterP2.phase)
            assertTrue(afterP2.actedThisPhase.isEmpty())
        }

        test("four player narrative waits for every participant and keeps private intel isolated") {
            val participants = setOf("p1", "p2", "p3", "p4")
            var state = ArcEngine.start(marineContext().copy(participantIds = participants))

            assertTrue(ArcEngine.view(state, "p3").choices.isNotEmpty())
            assertTrue(ArcEngine.view(state, "p4").choices.isNotEmpty())

            state = ArcEngine.choose(state, "p1", "help_locals").state
            state = ArcEngine.choose(state, "p2", "survey_route").state
            state = ArcEngine.choose(state, "p3", "shadow_authority").state
            assertEquals(ArcPhase.ARRIVAL, state.phase)
            assertTrue(state.privateClues["p3"].orEmpty().isNotEmpty())
            assertTrue(state.privateClues["p1"].orEmpty().isEmpty())
            assertTrue(state.privateClues["p4"].orEmpty().isEmpty())

            state = ArcEngine.choose(state, "p4", "survey_route").state
            assertEquals(ArcPhase.INVESTIGATION, state.phase)
            assertTrue(state.actedThisPhase.isEmpty())
            assertTrue(ArcEngine.view(state, "p3").choices.any { it.id == "reveal_intel" })

            val revealed = ArcEngine.choose(state, "p3", "reveal_intel")
            assertTrue(revealed.state.sharedFlags.any { it.startsWith("INTEL_REVEALED:") })
            assertTrue(revealed.beatsFor("p1").isNotEmpty())
            assertTrue(revealed.beatsFor("p2").isNotEmpty())
            assertTrue(revealed.beatsFor("p4").isNotEmpty())
        }

        test("revealing a private clue makes it shared on the next phase") {
            var state = ArcEngine.start(marineContext())
            state = ArcEngine.choose(state, "p1", "help_locals").state
            state = ArcEngine.choose(state, "p2", "shadow_authority").state
            assertEquals(ArcPhase.INVESTIGATION, state.phase)
            assertTrue(ArcEngine.view(state, "p2").choices.any { it.id == "reveal_intel" })
            val revealed = ArcEngine.choose(state, "p2", "reveal_intel")
            assertTrue(revealed.state.sharedFlags.any { it.startsWith("INTEL_REVEALED:") })
            assertTrue(revealed.beatsFor("p1").isNotEmpty())
        }

        test("aggressive cooperative choices raise deterministic escalation") {
            var state = ArcEngine.start(marineContext())
            state = ArcEngine.choose(state, "p1", "approach_openly").state
            state = ArcEngine.choose(state, "p2", "shadow_authority").state
            state = ArcEngine.choose(state, "p1", "force_information").state
            state = ArcEngine.choose(state, "p2", "keep_intel").state
            assertEquals(ArcPhase.ESCALATION, state.phase)
            assertEquals(2, state.escalation)
        }

        test("same arc state and choices resolve identically") {
            val base = ArcEngine.start(marineContext())
            val a = ArcEngine.choose(base, "p1", "approach_openly")
            val b = ArcEngine.choose(base, "p1", "approach_openly")
            assertEquals(a, b)
        }
    }

    private fun marineContext() = ArcStartContext(
        seed = 77L,
        islandId = "ironwake-atoll",
        presentFactions = setOf("MARINES"),
        worldFlags = setOf("MARINE_RESPONSE_CAPTAIN"),
        totalBounty = 20_000_000L,
    )
}