package grandlineduo.game.crew

import grandlineduo.game.ship.ShipCompartment
import grandlineduo.game.ship.ShipEngine
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object CrewEngineTest {
    fun register() {
        test("crew capacity follows ship capacity and crew quarters add two slots") {
            val ship = ShipEngine.starterShip("g", "Gull")
            assertEquals(ship.capacity, CrewEngine.capacityFor(ship))
            val withQuarters = ShipEngine.installCompartment(ship, ShipCompartment.CREW_QUARTERS)
            assertEquals(ship.capacity + 2, CrewEngine.capacityFor(withQuarters))
        }

        test("recruitment enforces capacity and rejects duplicate NPC id") {
            val ship = ShipEngine.starterShip("g", "Gull").copy(capacity = 1)
            val nami = member("nami", CrewRole.NAVIGATOR)
            val usopp = member("usopp", CrewRole.GUNNER)
            val crew = CrewEngine.recruit(CrewState(), ship, nami)
            assertEquals(nami, crew.members.getValue("nami"))

            var duplicateRejected = false
            try { CrewEngine.recruit(crew, ship, nami) } catch (_: IllegalArgumentException) { duplicateRejected = true }
            assertTrue(duplicateRejected)

            var capacityRejected = false
            try { CrewEngine.recruit(crew, ship, usopp) } catch (_: IllegalArgumentException) { capacityRejected = true }
            assertTrue(capacityRejected)
        }

        test("wounds reduce effective competence and unavailable crew contributes zero") {
            val healthy = member("franky", CrewRole.CARPENTER, competence = 5)
            assertEquals(5, CrewEngine.effectiveCompetence(healthy))

            val wounded = CrewEngine.injure(healthy, severity = 2)
            assertEquals(CrewStatus.WOUNDED, wounded.status)
            assertEquals(3, CrewEngine.effectiveCompetence(wounded))

            val captured = CrewEngine.capture(wounded)
            assertEquals(0, CrewEngine.effectiveCompetence(captured))
            val missing = CrewEngine.markMissing(healthy)
            assertEquals(0, CrewEngine.effectiveCompetence(missing))
        }

        test("healing removes wounds but death is terminal") {
            val wounded = CrewEngine.injure(member("chopper", CrewRole.DOCTOR, competence = 4), 3)
            val healed = CrewEngine.heal(wounded, 2)
            assertEquals(1, healed.injurySeverity)
            assertEquals(CrewStatus.WOUNDED, healed.status)
            val fullyHealed = CrewEngine.heal(healed, 2)
            assertEquals(0, fullyHealed.injurySeverity)
            assertEquals(CrewStatus.ACTIVE, fullyHealed.status)

            val dead = CrewEngine.kill(fullyHealed)
            var rejected = false
            try { CrewEngine.heal(dead, 3) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }

        test("loyalty and four player affinity are clamped to minus one hundred through one hundred") {
            var crew = member("robin", CrewRole.LOOKOUT, loyalty = 95)
            crew = CrewEngine.changeLoyalty(crew, 50)
            assertEquals(100, crew.loyalty)
            crew = CrewEngine.changeAffinity(crew, "p1", -250)
            crew = CrewEngine.changeAffinity(crew, "p2", 250)
            crew = CrewEngine.changeAffinity(crew, "p3", 75)
            crew = CrewEngine.changeAffinity(crew, "p4", -80)
            assertEquals(-100, crew.playerAffinity.getValue("p1"))
            assertEquals(100, crew.playerAffinity.getValue("p2"))
            assertEquals(75, crew.playerAffinity.getValue("p3"))
            assertEquals(-80, crew.playerAffinity.getValue("p4"))

            var invalidRejected = false
            try { CrewEngine.changeAffinity(crew, "p5", 1) } catch (_: IllegalArgumentException) { invalidRejected = true }
            assertTrue(invalidRejected)
        }

        test("low loyalty member deserts deterministically during a severe loyalty crisis") {
            val shaky = member("gin", CrewRole.GUNNER, loyalty = -55)
            val deserted = CrewEngine.resolveDesertion(shaky, severeCrisis = true)
            assertEquals(CrewStatus.DESERTED, deserted.status)
            assertEquals(0, CrewEngine.effectiveCompetence(deserted))

            val loyal = member("vivi", CrewRole.NAVIGATOR, loyalty = 30)
            assertEquals(CrewStatus.ACTIVE, CrewEngine.resolveDesertion(loyal, severeCrisis = true).status)
        }
    }

    private fun member(
        id: String,
        role: CrewRole,
        competence: Int = 3,
        loyalty: Int = 20,
    ) = CrewMemberState(
        npcId = id,
        name = id.replaceFirstChar { it.uppercase() },
        role = role,
        competence = competence,
        loyalty = loyalty,
        playerAffinity = mapOf("p1" to 0, "p2" to 0, "p3" to 0, "p4" to 0),
    )
}
