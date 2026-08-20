package grandlineduo.game.social

import grandlineduo.game.director.DirectorContext
import grandlineduo.game.director.DirectorDifficulty
import grandlineduo.game.director.DirectorEvent
import grandlineduo.game.director.DirectorEventKind
import grandlineduo.game.director.GrandLineDirector
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object SocialDirectorBridgeTest {
    fun register() {
        test("present hostile faction becomes an explicit Director justification flag") {
            val social = SocialState(factionStanding = mapOf("ARASHI_KINGDOM" to -80, "DOCKWORKERS" to 70))
            val absent = SocialDirectorBridge.flagsFor(social, emptySet())
            val present = SocialDirectorBridge.flagsFor(social, setOf("ARASHI_KINGDOM"))
            assertTrue(SocialDirectorBridge.PRESENT_HOSTILE_FACTION !in absent)
            assertTrue(SocialDirectorBridge.PRESENT_HOSTILE_FACTION in present)
        }

        test("default Director catalog contains ally opportunity and socially justified hostility") {
            val catalog = GrandLineDirector.defaultCatalog()
            val ally = catalog.first { it.id == "trusted-contact-tipoff" }
            val hostile = catalog.first { it.id == "hostile-faction-pressure" }
            assertEquals(SocialWorldFlags.HAS_ALLY, ally.requiredFlag)
            assertEquals(SocialDirectorBridge.PRESENT_HOSTILE_FACTION, hostile.requiredFlag)
            assertEquals(DirectorEventKind.THREAT, hostile.kind)
        }

        test("hostile social event remains blocked by threat budget when party is badly wounded") {
            val hostile = DirectorEvent(
                "hostile-social",
                "Hostile faction pressure",
                DirectorEventKind.THREAT,
                4,
                requiredFlag = SocialDirectorBridge.PRESENT_HOSTILE_FACTION,
            )
            val relief = DirectorEvent("social-relief", "Safe contact", DirectorEventKind.RELIEF, 0)
            val social = SocialState(factionStanding = mapOf("ARASHI_KINGDOM" to -90))
            val flags = SocialDirectorBridge.flagsFor(social, setOf("ARASHI_KINGDOM"))
            val director = GrandLineDirector(listOf(hostile, relief))
            val wounded = context(flags, currentHp = 8, maxHp = 40)
            val healthy = context(flags, currentHp = 40, maxHp = 40)

            assertTrue(director.threatBudget(wounded) < hostile.threatCost)
            assertTrue(director.threatBudget(healthy) >= hostile.threatCost)
            assertEquals("social-relief", director.choose(wounded)!!.event.id)
        }
    }

    private fun context(flags: Set<String>, currentHp: Int, maxHp: Int) = DirectorContext(
        seed = 67,
        decisionIndex = 2,
        islandId = "stormglass-cay",
        difficulty = DirectorDifficulty.NORMAL,
        totalBounty = 0,
        currentPartyHp = currentHp,
        maxPartyHp = maxHp,
        presentFactions = setOf("ARASHI_KINGDOM"),
        worldFlags = flags,
        recentEventIds = emptyList(),
    )
}
