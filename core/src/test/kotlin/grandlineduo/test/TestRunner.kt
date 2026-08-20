package grandlineduo.test

data class TestCase(val name: String, val body: () -> Unit)

object TestRegistry {
    private val cases = mutableListOf<TestCase>()
    fun add(name: String, body: () -> Unit) { cases += TestCase(name, body) }
    fun all(): List<TestCase> = cases.toList()
}

fun test(name: String, body: () -> Unit) = TestRegistry.add(name, body)

fun assertEquals(expected: Any?, actual: Any?, message: String = "") {
    if (expected != actual) error("Expected <$expected>, got <$actual>${if (message.isBlank()) "" else ": $message"}")
}

fun assertNotEquals(unexpected: Any?, actual: Any?, message: String = "") {
    if (unexpected == actual) error("Did not expect <$actual>${if (message.isBlank()) "" else ": $message"}")
}

fun assertTrue(value: Boolean, message: String = "") {
    if (!value) error(if (message.isBlank()) "Expected true" else message)
}

fun main() {
    grandlineduo.core.hash.CanonicalStateHasherTest.register()
    grandlineduo.core.events.EventReducerTest.register()
    grandlineduo.core.commands.CommandIdempotencyTest.register()
    grandlineduo.core.persistence.SnapshotStoreTest.register()
    grandlineduo.core.persistence.EventLogTest.register()
    grandlineduo.core.persistence.CampaignRecoveryTest.register()
    grandlineduo.core.persistence.DurableCampaignStoreTest.register()
    grandlineduo.core.network.ReconnectPlannerTest.register()
    grandlineduo.core.network.ReplicaSyncTest.register()
    grandlineduo.core.network.WireCodecTest.register()
    grandlineduo.core.network.LanTransportIntegrationTest.register()
    grandlineduo.core.network.LanGameplayIntegrationTest.register()
    grandlineduo.core.network.LanDiscoveryTest.register()
    grandlineduo.appshell.ShellStateTest.register()
    grandlineduo.appshell.GameVisualProfileTest.register()
    grandlineduo.appshell.CharacterPresetFactoryTest.register()
    grandlineduo.appshell.GameSessionCoordinatorTest.register()
    grandlineduo.appshell.GamePresenterTest.register()
    grandlineduo.appshell.CampaignLoopTest.register()
    grandlineduo.appshell.LanShellSessionCoordinatorTest.register()
    grandlineduo.sim.ConvergenceSimulationTest.register()
    grandlineduo.game.director.GrandLineDirectorTest.register()
    grandlineduo.game.arc.ArcEngineTest.register()
    grandlineduo.game.arc.ArcDirectorBridgeTest.register()
    grandlineduo.game.arc.ArcPersistenceTest.register()
    grandlineduo.game.arc.ArcCoordinatorTest.register()
    grandlineduo.game.arc.ClassMasteryArcIntegrationTest.register()
    grandlineduo.game.arc.ArcLanIntegrationTest.register()
    grandlineduo.game.arc.ArcCombatPersistenceTest.register()
    grandlineduo.game.arc.ArcBossFactoryTest.register()
    grandlineduo.game.arc.ArcCombatCoordinatorTest.register()
    grandlineduo.game.arc.ArcBossLanIntegrationTest.register()
    grandlineduo.game.scenario.StormglassCayScenarioTest.register()
    grandlineduo.game.combat.CombatEngineTest.register()
    grandlineduo.game.combat.CombatModifierResolverTest.register()
    grandlineduo.game.StormglassPersistenceAdapterTest.register()
    grandlineduo.game.VerticalSliceIntegrationTest.register()
    grandlineduo.game.InventoryEngineTest.register()
    grandlineduo.game.ShopEngineTest.register()
    grandlineduo.game.WorldActionIntegrationTest.register()
    grandlineduo.game.InventoryCommandIntegrationTest.register()
    grandlineduo.game.character.CharacterCreationTest.register()
    grandlineduo.game.character.CharacterPersistenceTest.register()
    grandlineduo.game.character.ProgressionEngineTest.register()
    grandlineduo.game.character.ClassMasteryEngineTest.register()
    grandlineduo.game.character.ClassMasteryPersistenceTest.register()
    grandlineduo.game.character.ClassMasteryTrainingIntegrationTest.register()
    grandlineduo.game.character.CharacterCoopIntegrationTest.register()
    grandlineduo.game.powers.HakiEngineTest.register()
    grandlineduo.game.powers.DevilFruitEngineTest.register()
    grandlineduo.game.powers.PowerPersistenceTest.register()
    grandlineduo.game.powers.PowerDiscoveryEngineTest.register()
    grandlineduo.game.powers.PowerTechniqueEngineTest.register()
    grandlineduo.game.powers.TrainingIntegrationTest.register()
    grandlineduo.game.powers.PowerCombatIntegrationTest.register()
    grandlineduo.game.powers.PowerCoopIntegrationTest.register()
    grandlineduo.game.notoriety.BountyEngineTest.register()
    grandlineduo.game.notoriety.MarineResponsePlannerTest.register()
    grandlineduo.game.notoriety.MarineDirectorIntegrationTest.register()
    grandlineduo.game.notoriety.NotorietyPersistenceTest.register()
    grandlineduo.game.notoriety.NotorietyCoordinatorTest.register()
    grandlineduo.game.social.SocialConsequenceEngineTest.register()
    grandlineduo.game.social.SocialPersistenceTest.register()
    grandlineduo.game.social.SocialCoordinatorTest.register()
    grandlineduo.game.social.SocialDirectorBridgeTest.register()
    grandlineduo.game.ship.ShipEngineTest.register()
    grandlineduo.game.ship.ShipPersistenceTest.register()
    grandlineduo.game.ship.VoyageEngineTest.register()
    grandlineduo.game.ship.ClassMasteryVoyageIntegrationTest.register()
    grandlineduo.game.ship.ShipCoopIntegrationTest.register()
    grandlineduo.game.ship.ShipDirectorBridgeTest.register()
    grandlineduo.game.crew.CrewEngineTest.register()
    grandlineduo.game.crew.CrewPersistenceTest.register()
    grandlineduo.game.crew.CrewVoyageIntegrationTest.register()
    grandlineduo.game.crew.CrewCoordinatorTest.register()
    grandlineduo.game.crew.CrewDirectorBridgeTest.register()
    grandlineduo.game.world.GrandLineWorldAtlasTest.register()
    grandlineduo.game.world.ExplorationEngineTest.register()
    grandlineduo.game.world.ExplorationCommandIntegrationTest.register()
    grandlineduo.game.world.ExplorationViewportTest.register()

    var failed = 0
    for (case in TestRegistry.all()) {
        try {
            case.body()
            println("PASS ${case.name}")
        } catch (t: Throwable) {
            failed++
            println("FAIL ${case.name}: ${t.message}")
        }
    }
    println("RESULT ${TestRegistry.all().size - failed}/${TestRegistry.all().size} passed")
    if (failed > 0) error("$failed test(s) failed")
}
