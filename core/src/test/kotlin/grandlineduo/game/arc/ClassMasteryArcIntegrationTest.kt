package grandlineduo.game.arc

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.character.ClassMasteryEngine
import grandlineduo.game.character.ClassPath
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object ClassMasteryArcIntegrationTest {
    fun register() {
        test("scholar primary mastery turns investigation into shared tactical analysis") {
            val scholar = playerWithPrimaryClass(ClassPath.SCHOLAR, level = 10)
            val host = HostReplica(world("scholar-analysis", scholar, investigationArc()))
            ArcCoordinator(host).choose("scholar-investigate", "p1", "question_contacts", 1_000)

            assertTrue("SCHOLAR_ANALYSIS_TIER:2" in host.state.activeArc!!.sharedFlags)
        }

        test("secondary scholar mastery does not replace primary investigative identity") {
            val created = CharacterCreation.create(
                CharacterCreationTest.validDraft().copy(classPath = ClassPath.SWORDSMAN)
            ) as CharacterCreationResult.Success
            var mastery = created.profile.classMastery!!
            while (mastery.levelOf(ClassPath.SCHOLAR) < 10) {
                mastery = ClassMasteryEngine.train(
                    mastery,
                    ClassPath.SCHOLAR,
                    ClassMasteryEngine.experienceRequiredForLevel(mastery.levelOf(ClassPath.SCHOLAR)),
                )
            }
            val player = PlayerState(
                playerId = "p1",
                name = created.profile.name,
                hp = created.profile.maxHp,
                maxHp = created.profile.maxHp,
                bounty = 0,
                energy = created.profile.maxEnergy,
                maxEnergy = created.profile.maxEnergy,
                profile = created.profile.copy(classMastery = mastery),
            )
            val host = HostReplica(world("secondary-scholar", player, investigationArc()))
            ArcCoordinator(host).choose("secondary-investigate", "p1", "question_contacts", 1_100)

            assertTrue(host.state.activeArc!!.sharedFlags.none { it.startsWith("SCHOLAR_ANALYSIS_TIER:") })
        }

        test("scholar analysis lowers arc boss hp and attack without changing boss identity") {
            val baseWorld = WorldState(
                campaignId = "scholar-boss",
                islandId = "ironwake-atoll",
                players = mapOf(
                    "p1" to PlayerState("p1", "Kairo", 30, 30, 0, 15, 15),
                    "p2" to PlayerState("p2", "Namiya", 30, 30, 0, 15, 15),
                ),
            )
            val baseArc = ArcState(
                arcId = "ironwake:marine:77",
                islandId = "ironwake-atoll",
                seed = 77,
                archetype = ArcArchetype.MARINE_OCCUPATION,
                phase = ArcPhase.CLIMAX,
                escalation = 4,
            )
            val normal = ArcBossFactory.create(baseWorld, baseArc)
            val analyzed = ArcBossFactory.create(
                baseWorld,
                baseArc.copy(sharedFlags = setOf("SCHOLAR_ANALYSIS_TIER:2")),
            )

            assertEquals(normal.enemy.id, analyzed.enemy.id)
            assertEquals(normal.enemy.name, analyzed.enemy.name)
            assertEquals(normal.enemy.maxHp - 12, analyzed.enemy.maxHp)
            assertEquals(normal.enemy.attackPower - 2, analyzed.enemy.attackPower)
        }
    }

    private fun investigationArc() = ArcState(
        arcId = "ironwake:marine:42",
        islandId = "ironwake-atoll",
        seed = 42,
        archetype = ArcArchetype.MARINE_OCCUPATION,
        phase = ArcPhase.INVESTIGATION,
    )

    private fun playerWithPrimaryClass(path: ClassPath, level: Int): PlayerState {
        val created = CharacterCreation.create(
            CharacterCreationTest.validDraft().copy(classPath = path)
        ) as CharacterCreationResult.Success
        var mastery = created.profile.classMastery!!
        while (mastery.levelOf(path) < level) {
            mastery = ClassMasteryEngine.train(
                mastery,
                path,
                ClassMasteryEngine.experienceRequiredForLevel(mastery.levelOf(path)),
            )
        }
        val profile = created.profile.copy(classMastery = mastery)
        return PlayerState(
            playerId = "p1",
            name = profile.name,
            hp = profile.maxHp,
            maxHp = profile.maxHp,
            bounty = 0,
            energy = profile.maxEnergy,
            maxEnergy = profile.maxEnergy,
            profile = profile,
        )
    }

    private fun world(id: String, p1: PlayerState, arc: ArcState) = WorldState(
        campaignId = id,
        islandId = arc.islandId,
        players = mapOf(
            "p1" to p1,
            "p2" to PlayerState("p2", "Namiya", 30, 30, 0, 15, 15),
        ),
        activeArc = arc,
    )
}
