package grandlineduo.game.ship

import grandlineduo.game.director.DirectorContext
import grandlineduo.game.director.DirectorDifficulty
import grandlineduo.game.director.DirectorEvent
import grandlineduo.game.director.DirectorEventKind
import grandlineduo.game.director.GrandLineDirector
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ShipDirectorBridgeTest {
    fun register() {
        test("ship condition exposes typed Director flags only when thresholds are crossed") {
            val healthy = ShipEngine.starterShip("gull", "Black Gull")
            val damaged = healthy.copy(hull = 30, supplies = 10)
            val critical = healthy.copy(hull = 15, supplies = 0, artillery = 5)

            assertEquals(emptySet<String>(), ShipDirectorBridge.flagsFor(healthy))

            val damagedFlags = ShipDirectorBridge.flagsFor(damaged)
            assertTrue(ShipDirectorBridge.SHIP_DAMAGED in damagedFlags)
            assertTrue(ShipDirectorBridge.SHIP_CRITICAL !in damagedFlags)
            assertTrue(ShipDirectorBridge.SHIP_LOW_SUPPLIES in damagedFlags)

            val criticalFlags = ShipDirectorBridge.flagsFor(critical)
            assertTrue(ShipDirectorBridge.SHIP_DAMAGED in criticalFlags)
            assertTrue(ShipDirectorBridge.SHIP_CRITICAL in criticalFlags)
            assertTrue(ShipDirectorBridge.SHIP_LOW_SUPPLIES in criticalFlags)
            assertTrue(ShipDirectorBridge.SHIP_NO_SUPPLIES in criticalFlags)
            assertTrue(ShipDirectorBridge.SHIP_WELL_ARMED in criticalFlags)
        }

        test("default Director catalog offers repair and supplies only for justified ship conditions") {
            val catalog = GrandLineDirector.defaultCatalog()
            val repair = catalog.first { it.id == "hidden-repair-cove" }
            val supplies = catalog.first { it.id == "drifting-supply-wreckage" }

            assertEquals(DirectorEventKind.RELIEF, repair.kind)
            assertEquals(ShipDirectorBridge.SHIP_DAMAGED, repair.requiredFlag)
            assertEquals(DirectorEventKind.RELIEF, supplies.kind)
            assertEquals(ShipDirectorBridge.SHIP_LOW_SUPPLIES, supplies.requiredFlag)
        }

        test("well armed ship does not force naval threat above Director budget") {
            val threat = DirectorEvent(
                id = "naval-challenge-test",
                title = "Rival ship challenges the crew",
                kind = DirectorEventKind.THREAT,
                threatCost = 6,
                requiredFlag = ShipDirectorBridge.SHIP_WELL_ARMED,
            )
            val relief = DirectorEvent(
                id = "ship-relief-test",
                title = "Quiet waters",
                kind = DirectorEventKind.RELIEF,
                threatCost = 0,
            )
            val director = GrandLineDirector(listOf(threat, relief))
            val flags = ShipDirectorBridge.flagsFor(
                ShipEngine.starterShip("gull", "Black Gull").copy(artillery = 5),
            )
            val wounded = context(flags, currentHp = 6, maxHp = 40)
            val healthy = context(flags, currentHp = 40, maxHp = 40)

            assertTrue(director.threatBudget(wounded) < threat.threatCost)
            assertTrue(director.threatBudget(healthy) >= threat.threatCost)
            assertEquals("ship-relief-test", director.choose(wounded)!!.event.id)
        }
    }

    private fun context(flags: Set<String>, currentHp: Int, maxHp: Int) = DirectorContext(
        seed = 91,
        decisionIndex = 3,
        islandId = "open-sea",
        difficulty = DirectorDifficulty.NORMAL,
        totalBounty = 20_000_000,
        currentPartyHp = currentHp,
        maxPartyHp = maxHp,
        presentFactions = setOf("PIRATES"),
        worldFlags = flags,
        recentEventIds = emptyList(),
    )
}
