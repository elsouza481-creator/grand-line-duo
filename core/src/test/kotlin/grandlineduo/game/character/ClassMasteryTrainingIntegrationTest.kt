package grandlineduo.game.character

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ClassMasteryTrainingIntegrationTest {
    fun register() {
        test("authoritative class training grants fixed effort and ignores client supplied amount") {
            val profile = createdProfile(ClassPath.NAVIGATOR)
            val host = HostReplica(world(profile))
            val handler = StormglassGameplayCommandHandler(host, seed = 77)
            val beforeHash = CanonicalStateHasher.hash(host.state)

            handler.handle(
                GameplayWireCommand.WorldAction(
                    commandId = "class-train-nav",
                    actorId = "p1",
                    actionType = "TRAIN_CLASS",
                    target = ClassPath.NAVIGATOR.name,
                    amount = 999,
                ),
                1_000,
            )

            val mastery = host.state.players.getValue("p1").profile!!.classMastery!!
            assertEquals(ClassPath.NAVIGATOR, mastery.primaryClass)
            assertEquals(25L, mastery.experienceOf(ClassPath.NAVIGATOR))
            assertNotEquals(beforeHash, CanonicalStateHasher.hash(host.state))
        }

        test("secondary class training never replaces the primary class") {
            val profile = createdProfile(ClassPath.NAVIGATOR)
            val host = HostReplica(world(profile))
            val handler = StormglassGameplayCommandHandler(host, seed = 88)

            handler.handle(
                GameplayWireCommand.WorldAction(
                    commandId = "class-train-gunner",
                    actorId = "p1",
                    actionType = "TRAIN_CLASS",
                    target = ClassPath.GUNNER.name,
                    amount = 1,
                ),
                1_100,
            )

            val mastery = host.state.players.getValue("p1").profile!!.classMastery!!
            assertEquals(ClassPath.NAVIGATOR, mastery.primaryClass)
            assertEquals(25L, mastery.experienceOf(ClassPath.GUNNER))
        }

        test("legacy character can choose one primary class exactly once") {
            val legacy = createdProfile(null)
            val host = HostReplica(world(legacy))
            val handler = StormglassGameplayCommandHandler(host, seed = 99)

            handler.handle(
                GameplayWireCommand.WorldAction(
                    commandId = "class-choose",
                    actorId = "p1",
                    actionType = "CHOOSE_CLASS",
                    target = ClassPath.CAPTAIN.name,
                    amount = 1,
                ),
                1_200,
            )
            assertEquals(
                ClassPath.CAPTAIN,
                host.state.players.getValue("p1").profile!!.classMastery!!.primaryClass,
            )

            val stateAfterChoice = host.state
            var rejected = false
            try {
                handler.handle(
                    GameplayWireCommand.WorldAction(
                        commandId = "class-change",
                        actorId = "p1",
                        actionType = "CHOOSE_CLASS",
                        target = ClassPath.SWORDSMAN.name,
                        amount = 1,
                    ),
                    1_201,
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(stateAfterChoice, host.state)
        }
    }

    private fun createdProfile(path: ClassPath?): CharacterProfile =
        (CharacterCreation.create(CharacterCreationTest.validDraft().copy(classPath = path)) as CharacterCreationResult.Success).profile

    private fun world(profile: CharacterProfile) = WorldState(
        campaignId = "class-training",
        islandId = "stormglass-cay",
        partyBerries = 5_000,
        players = mapOf(
            "p1" to PlayerState(
                playerId = "p1",
                name = profile.name,
                hp = profile.maxHp,
                maxHp = profile.maxHp,
                bounty = 0,
                energy = profile.maxEnergy,
                maxEnergy = profile.maxEnergy,
                profile = profile,
            ),
            "p2" to PlayerState("p2", "Companion", 20, 20, 0),
        ),
    )
}
