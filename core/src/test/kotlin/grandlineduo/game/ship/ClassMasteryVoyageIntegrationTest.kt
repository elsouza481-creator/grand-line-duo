package grandlineduo.game.ship

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.character.CharacterProfile
import grandlineduo.game.character.ClassPath
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ClassMasteryVoyageIntegrationTest {
    fun register() {
        test("navigator mastery improves the helm action during a storm") {
            val incident = VoyageIncident(VoyageIncidentType.STORM, severity = 4, seed = 777)
            val baseline = resolve(incident, VoyageAction.HELM, VoyageAction.LOOKOUT)
            val mastered = resolve(
                incident,
                VoyageAction.HELM,
                VoyageAction.LOOKOUT,
                p1Profile = profile(ClassPath.NAVIGATOR, 25),
            )

            assertTrue(mastered.hullDamage < baseline.hullDamage)
        }

        test("cook mastery protects more supplies when that player guards provisions") {
            val incident = VoyageIncident(VoyageIncidentType.STORM, severity = 5, seed = 991)
            val baseline = resolve(incident, VoyageAction.PROTECT_SUPPLIES, VoyageAction.LOOKOUT)
            val mastered = resolve(
                incident,
                VoyageAction.PROTECT_SUPPLIES,
                VoyageAction.LOOKOUT,
                p1Profile = profile(ClassPath.COOK, 25),
            )

            assertTrue(mastered.supplyLoss < baseline.supplyLoss)
        }

        test("shipwright mastery reduces hull damage while repairing at sea") {
            val incident = VoyageIncident(VoyageIncidentType.STORM, severity = 4, seed = 444)
            val baseline = resolve(incident, VoyageAction.REPAIR, VoyageAction.LOOKOUT)
            val mastered = resolve(
                incident,
                VoyageAction.REPAIR,
                VoyageAction.LOOKOUT,
                p1Profile = profile(ClassPath.SHIPWRIGHT, 25),
            )

            assertTrue(mastered.hullDamage < baseline.hullDamage)
        }

        test("utility mastery only applies when the player performs its matching ship duty") {
            val incident = VoyageIncident(VoyageIncidentType.STORM, severity = 4, seed = 444)
            val baseline = resolve(incident, VoyageAction.REPAIR, VoyageAction.LOOKOUT)
            val wrongDuty = resolve(
                incident,
                VoyageAction.REPAIR,
                VoyageAction.LOOKOUT,
                p1Profile = profile(ClassPath.NAVIGATOR, 50),
            )

            assertEquals(baseline, wrongDuty)
        }
    }

    private data class Outcome(
        val success: Boolean,
        val hullDamage: Int,
        val supplyLoss: Int,
    )

    private fun resolve(
        incident: VoyageIncident,
        p1Action: VoyageAction,
        p2Action: VoyageAction,
        p1Profile: CharacterProfile? = null,
    ): Outcome {
        val ship = ShipEngine.starterShip("class-voyage", "Class Voyage")
        val initial = WorldState(
            campaignId = "class-voyage-${incident.seed}-${p1Profile?.classMastery?.primaryClass?.name ?: "baseline"}",
            islandId = "open-sea",
            shipState = ship,
            activeVoyage = VoyageEncounter(incident),
            players = mapOf(
                "p1" to PlayerState("p1", "Kairo", 30, 30, 0, profile = p1Profile),
                "p2" to PlayerState("p2", "Namiya", 30, 30, 0),
            ),
        )
        val host = HostReplica(initial)
        val handler = StormglassGameplayCommandHandler(host, seed = incident.seed)
        handler.handle(
            GameplayWireCommand.VoyageAction("class-voyage-p1", "p1", p1Action.name),
            1_000,
        )
        val event = handler.handle(
            GameplayWireCommand.VoyageAction("class-voyage-p2", "p2", p2Action.name),
            1_001,
        )
        return Outcome(
            success = event.payload.getValue("meta.voyageSuccess").toBoolean(),
            hullDamage = event.payload.getValue("meta.voyageHullDamage").toInt(),
            supplyLoss = event.payload.getValue("meta.voyageSupplyLoss").toInt(),
        )
    }

    private fun profile(path: ClassPath, level: Int): CharacterProfile {
        val created = CharacterCreation.create(
            CharacterCreationTest.validDraft().copy(classPath = path),
        ) as CharacterCreationResult.Success
        val mastery = created.profile.classMastery ?: error("missing class mastery")
        return created.profile.copy(
            classMastery = mastery.copy(levels = mapOf(path to level)),
        )
    }
}
