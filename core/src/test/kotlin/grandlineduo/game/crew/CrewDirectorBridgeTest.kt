package grandlineduo.game.crew

import grandlineduo.game.director.DirectorContext
import grandlineduo.game.director.DirectorDifficulty
import grandlineduo.game.director.DirectorEventKind
import grandlineduo.game.director.GrandLineDirector
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object CrewDirectorBridgeTest {
    fun register() {
        test("crew bridge exposes only available specialist roles") {
            val capturedDoctor = CrewEngine.capture(member("doc", CrewRole.DOCTOR, 5))
            val crew = CrewState(
                mapOf(
                    "doc" to capturedDoctor,
                    "carp" to member("carp", CrewRole.CARPENTER, 4),
                    "nav" to member("nav", CrewRole.NAVIGATOR, 3),
                ),
            )
            val flags = CrewDirectorBridge.flagsFor(crew)
            assertEquals(false, CrewDirectorBridge.HAS_DOCTOR in flags)
            assertTrue(CrewDirectorBridge.HAS_CARPENTER in flags)
            assertTrue(CrewDirectorBridge.HAS_NAVIGATOR in flags)
            assertTrue(CrewDirectorBridge.MEMBER_CAPTURED in flags)
        }

        test("low loyalty and missing crew become explicit GM justifications") {
            val missing = CrewEngine.markMissing(member("look", CrewRole.LOOKOUT, 4, loyalty = 10))
            val shaky = member("gun", CrewRole.GUNNER, 3, loyalty = -60)
            val flags = CrewDirectorBridge.flagsFor(CrewState(mapOf("look" to missing, "gun" to shaky)))
            assertTrue(CrewDirectorBridge.LOW_LOYALTY in flags)
            assertTrue(CrewDirectorBridge.MEMBER_MISSING in flags)
        }

        test("default Director catalog contains crew rescue route and specialist opportunities") {
            val byId = GrandLineDirector.defaultCatalog().associateBy { it.id }
            assertEquals(CrewDirectorBridge.MEMBER_CAPTURED, byId.getValue("crew-rescue-lead").requiredFlag)
            assertEquals(CrewDirectorBridge.MEMBER_MISSING, byId.getValue("crew-search-trail").requiredFlag)
            assertEquals(CrewDirectorBridge.HAS_NAVIGATOR, byId.getValue("crew-secret-route").requiredFlag)
            assertEquals(CrewDirectorBridge.HAS_DOCTOR, byId.getValue("crew-field-treatment").requiredFlag)
        }

        test("low loyalty crisis remains blocked when party health collapses threat budget") {
            val director = GrandLineDirector(
                listOf(
                    GrandLineDirector.defaultCatalog().first { it.id == "crew-loyalty-crisis" },
                ),
            )
            val flags = CrewDirectorBridge.flagsFor(CrewState(mapOf("gin" to member("gin", CrewRole.GUNNER, 3, loyalty = -70))))
            val wounded = DirectorContext(
                seed = 5,
                decisionIndex = 1,
                islandId = "stormglass-cay",
                difficulty = DirectorDifficulty.RELAXED,
                totalBounty = 0,
                currentPartyHp = 5,
                maxPartyHp = 40,
                presentFactions = emptySet(),
                worldFlags = flags,
                recentEventIds = emptyList(),
            )
            assertEquals(null, director.choose(wounded))

            val healthy = wounded.copy(currentPartyHp = 40)
            val decision = director.choose(healthy)
            assertEquals("crew-loyalty-crisis", decision?.event?.id)
            assertEquals(DirectorEventKind.THREAT, decision?.event?.kind)
        }
    }

    private fun member(
        id: String,
        role: CrewRole,
        competence: Int,
        loyalty: Int = 20,
    ) = CrewMemberState(id, id, role, competence, loyalty = loyalty)
}
