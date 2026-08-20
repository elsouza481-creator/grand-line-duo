package grandlineduo.game.director

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object GrandLineDirectorTest {
    fun register() {
        test("director never selects an event above the computed threat budget") {
            val director = GrandLineDirector(
                listOf(
                    DirectorEvent("small-patrol", "Small patrol", DirectorEventKind.THREAT, 3, "MARINES"),
                    DirectorEvent("admiral", "Impossible force", DirectorEventKind.THREAT, 99, "MARINES"),
                )
            )
            val context = context(presentFactions = setOf("MARINES"))
            val decision = director.choose(context)!!
            assertTrue(decision.event.threatCost <= decision.threatBudget)
            assertEquals("small-patrol", decision.event.id)
        }

        test("low party health reduces threat budget and prefers relief") {
            val director = GrandLineDirector(
                listOf(
                    DirectorEvent("ambush", "Ambush", DirectorEventKind.THREAT, 3, "MARINES"),
                    DirectorEvent("safe-house", "Safe house", DirectorEventKind.RELIEF, 0),
                )
            )
            val healthy = context(currentHp = 40, maxHp = 40, presentFactions = setOf("MARINES"))
            val wounded = context(currentHp = 8, maxHp = 40, presentFactions = setOf("MARINES"))

            assertTrue(director.threatBudget(wounded) < director.threatBudget(healthy))
            assertEquals("safe-house", director.choose(wounded)!!.event.id)
        }

        test("director respects faction eligibility") {
            val director = GrandLineDirector(
                listOf(
                    DirectorEvent("marine-raid", "Marine raid", DirectorEventKind.THREAT, 2, "MARINES"),
                    DirectorEvent("storm", "Storm front", DirectorEventKind.OPPORTUNITY, 1),
                )
            )
            val decision = director.choose(context(presentFactions = setOf("PIRATES")))!!
            assertEquals("storm", decision.event.id)
        }

        test("recent events are excluded to prevent repetition") {
            val director = GrandLineDirector(
                listOf(
                    DirectorEvent("repeat-me", "Repeated", DirectorEventKind.OPPORTUNITY, 1),
                    DirectorEvent("fresh", "Fresh", DirectorEventKind.OPPORTUNITY, 1),
                )
            )
            val decision = director.choose(context(recentEventIds = listOf("repeat-me")))!!
            assertEquals("fresh", decision.event.id)
        }

        test("same context and seed produce the same director choice") {
            val director = GrandLineDirector(
                listOf(
                    DirectorEvent("a", "A", DirectorEventKind.OPPORTUNITY, 1),
                    DirectorEvent("b", "B", DirectorEventKind.OPPORTUNITY, 1),
                    DirectorEvent("c", "C", DirectorEventKind.OPPORTUNITY, 1),
                )
            )
            val context = context(seed = 991, decisionIndex = 8)
            assertEquals(director.choose(context), director.choose(context))
        }
    }

    private fun context(
        seed: Long = 123,
        decisionIndex: Long = 1,
        currentHp: Int = 40,
        maxHp: Int = 40,
        presentFactions: Set<String> = emptySet(),
        recentEventIds: List<String> = emptyList(),
    ) = DirectorContext(
        seed = seed,
        decisionIndex = decisionIndex,
        islandId = "stormglass-cay",
        difficulty = DirectorDifficulty.NORMAL,
        totalBounty = 2_000_000,
        currentPartyHp = currentHp,
        maxPartyHp = maxHp,
        presentFactions = presentFactions,
        worldFlags = emptySet(),
        recentEventIds = recentEventIds,
    )
}
