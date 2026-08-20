package grandlineduo.game.powers

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.character.Attribute
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object TrainingIntegrationTest {
    fun register() {
        test("host authoritative training evolves attributes and awakens Haki") {
            val created = (CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success).profile
            val profile = created.copy(evolutionPoints = 3)
            val p1 = PlayerState("p1", profile.name, profile.maxHp, profile.maxHp, 0, profile.maxEnergy, profile.maxEnergy, profile)
            var initial = WorldState("training", islandId = "stormglass-cay", players = mapOf("p1" to p1, "p2" to PlayerState("p2", "P2", 20, 20, 0)))
            val training = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).interactions.entries.single { it.value == ExplorationInteraction.TRAINING }.key
            initial = ExplorationEngine.place(initial, "p1", training)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 44)

            handler.handle(GameplayWireCommand.WorldAction("train-1", "p1", "TRAIN_ATTRIBUTE", "FOR", 1), 100)
            handler.handle(GameplayWireCommand.WorldAction("train-2", "p1", "UPGRADE_ATTRIBUTE", "FOR", 1), 101)
            handler.handle(GameplayWireCommand.WorldAction("haki-1", "p1", "HAKI_AWAKEN", "BUSOSHOKU", 1), 102)

            val after = host.state.players.getValue("p1").profile!!
            assertEquals(profile.attributes.getValue(Attribute.FOR) + 1, after.attributes.getValue(Attribute.FOR))
            assertEquals(0, after.evolutionPoints)
            assertTrue(HakiType.BUSOSHOKU in after.haki.disciplines)
        }

        test("discovered campaign fruit can be consumed only by authoritative world action") {
            val created = (CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success).profile
            val p1 = PlayerState("p1", created.name, created.maxHp, created.maxHp, 0, created.maxEnergy, created.maxEnergy, created)
            val discovery = PowerDiscoveryEngine.fruitDiscovery(55L)
            var initial = WorldState(
                "fruit-training",
                islandId = "stormglass-cay",
                players = mapOf("p1" to p1, "p2" to PlayerState("p2", "P2", 20, 20, 0)),
                worldFlags = mapOf("fruit.discovery.id" to discovery.definition.id),
            )
            val training = ExplorationEngine.mapFor(initial.campaignId, initial.islandId).interactions.entries.single { it.value == ExplorationInteraction.TRAINING }.key
            initial = ExplorationEngine.place(initial, "p1", training)
            val host = HostReplica(initial)
            val handler = StormglassGameplayCommandHandler(host, seed = 55L)
            handler.handle(GameplayWireCommand.WorldAction("fruit-eat", "p1", "FRUIT_EAT", discovery.definition.id, 1), 100)
            val after = host.state
            assertEquals(discovery.definition.id, after.players.getValue("p1").profile!!.devilFruit!!.fruitId)
            assertEquals(null, after.worldFlags["fruit.discovery.id"])
        }
    }
}
