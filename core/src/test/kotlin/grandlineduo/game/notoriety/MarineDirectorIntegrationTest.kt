package grandlineduo.game.notoriety

import grandlineduo.game.director.DirectorContext
import grandlineduo.game.director.DirectorDifficulty
import grandlineduo.game.director.DirectorEvent
import grandlineduo.game.director.DirectorEventKind
import grandlineduo.game.director.GrandLineDirector
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object MarineDirectorIntegrationTest {
    fun register() {
        test("default Director catalog exposes specialist Marine response behind response flag") {
            val event = GrandLineDirector.defaultCatalog().first { it.id == "marine-specialist-unit" }
            assertEquals("MARINES", event.requiredFaction)
            assertEquals("MARINE_RESPONSE_SPECIALIST", event.requiredFlag)
            assertEquals(7, event.threatCost)
        }

        test("specialist response is ineligible without Marine presence and response intelligence") {
            val specialist = DirectorEvent(
                id = "specialist",
                title = "Specialist",
                kind = DirectorEventKind.THREAT,
                threatCost = 7,
                requiredFaction = "MARINES",
                requiredFlag = "MARINE_RESPONSE_SPECIALIST",
            )
            val director = GrandLineDirector(listOf(specialist))

            assertEquals(null, director.choose(context(presentFactions = emptySet(), flags = setOf("MARINE_RESPONSE_SPECIALIST"))))
            assertEquals(null, director.choose(context(presentFactions = setOf("MARINES"), flags = emptySet())))
        }

        test("high notoriety can make specialist response eligible while low health still blocks immediate deployment") {
            val specialist = DirectorEvent(
                id = "specialist",
                title = "Specialist",
                kind = DirectorEventKind.THREAT,
                threatCost = 7,
                requiredFaction = "MARINES",
                requiredFlag = "MARINE_RESPONSE_SPECIALIST",
            )
            val relief = DirectorEvent("relief", "Relief", DirectorEventKind.RELIEF, 0)
            val director = GrandLineDirector(listOf(specialist, relief))
            val healthy = context(
                currentHp = 40,
                maxHp = 40,
                presentFactions = setOf("MARINES"),
                flags = setOf("MARINE_RESPONSE_SPECIALIST"),
            )
            val wounded = context(
                currentHp = 8,
                maxHp = 40,
                presentFactions = setOf("MARINES"),
                flags = setOf("MARINE_RESPONSE_SPECIALIST"),
            )

            assertTrue(director.threatBudget(healthy) >= specialist.threatCost)
            assertTrue(director.threatBudget(wounded) < specialist.threatCost)
            assertEquals("relief", director.choose(wounded)!!.event.id)
        }
    }

    private fun context(
        currentHp: Int = 40,
        maxHp: Int = 40,
        presentFactions: Set<String>,
        flags: Set<String>,
    ) = DirectorContext(
        seed = 44,
        decisionIndex = 3,
        islandId = "stormglass-cay",
        difficulty = DirectorDifficulty.NORMAL,
        totalBounty = 70_000_000,
        currentPartyHp = currentHp,
        maxPartyHp = maxHp,
        presentFactions = presentFactions,
        worldFlags = flags,
        recentEventIds = emptyList(),
    )
}
