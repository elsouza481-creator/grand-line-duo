package grandlineduo.game.combat

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.character.ClassMasteryState
import grandlineduo.game.character.ClassPath
import grandlineduo.game.powers.DevilFruitCategory
import grandlineduo.game.powers.DevilFruitState
import grandlineduo.game.powers.HakiDiscipline
import grandlineduo.game.powers.HakiState
import grandlineduo.game.powers.HakiType
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object CombatModifierResolverTest {
    fun register() {
        test("equipped weapon Haki and Devil Fruit contribute deterministic combat modifiers") {
            val base = (CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success).profile
            val powered = base.copy(
                haki = HakiState(disciplines = mapOf(
                    HakiType.BUSOSHOKU to HakiDiscipline(mastery = 2),
                    HakiType.KENBUNSHOKU to HakiDiscipline(mastery = 1),
                )),
                devilFruit = DevilFruitState("pulse-pulse", DevilFruitCategory.PARAMECIA, "Pulse-Pulse Fruit", mastery = 3),
            )
            var world = WorldState(
                campaignId = "mods",
                players = mapOf(
                    "p1" to PlayerState("p1", "A", powered.maxHp, powered.maxHp, 0, powered.maxEnergy, powered.maxEnergy, powered),
                    "p2" to PlayerState("p2", "B", 30, 30, 0),
                ),
            )
            world = InventoryEngine.grant(world, "p1", "iron_sabre", 1)
            world = InventoryEngine.equip(world, "p1", "iron_sabre")
            val modifier = CombatModifierResolver.forWorld(world).getValue("p1")
            assertEquals(13, modifier.attackBonus)
            assertEquals(3, modifier.damageReduction)

            val suppressed = CombatModifierResolver.forWorld(world.copy(worldFlags = world.worldFlags + ("status.p1.seastone" to "true"))).getValue("p1")
            assertEquals(8, suppressed.attackBonus)
            assertEquals(3, suppressed.damageReduction)
        }

        test("swordsman primary mastery adds milestone attack bonus") {
            val modifier = modifierFor(
                ClassMasteryState(
                    primaryClass = ClassPath.SWORDSMAN,
                    levels = mapOf(ClassPath.SWORDSMAN to 25),
                ),
            )

            assertEquals(3, modifier.attackBonus)
            assertEquals(0, modifier.damageReduction)
        }

        test("brawler primary mastery adds offense and close combat resilience") {
            val modifier = modifierFor(
                ClassMasteryState(
                    primaryClass = ClassPath.BRAWLER,
                    levels = mapOf(ClassPath.BRAWLER to 25),
                ),
            )

            assertEquals(3, modifier.attackBonus)
            assertEquals(2, modifier.damageReduction)
        }

        test("gunner rogue and captain have distinct deterministic combat roles") {
            val gunner = modifierFor(
                ClassMasteryState(ClassPath.GUNNER, levels = mapOf(ClassPath.GUNNER to 10)),
            )
            val rogue = modifierFor(
                ClassMasteryState(ClassPath.ROGUE, levels = mapOf(ClassPath.ROGUE to 10)),
            )
            val captain = modifierFor(
                ClassMasteryState(ClassPath.CAPTAIN, levels = mapOf(ClassPath.CAPTAIN to 10)),
            )

            assertEquals(2, gunner.attackBonus)
            assertEquals(0, gunner.damageReduction)
            assertEquals(2, rogue.attackBonus)
            assertEquals(0, rogue.damageReduction)
            assertEquals(1, captain.attackBonus)
            assertEquals(1, captain.damageReduction)
        }

        test("utility primary classes do not receive generic combat damage") {
            val modifier = modifierFor(
                ClassMasteryState(
                    primaryClass = ClassPath.NAVIGATOR,
                    levels = mapOf(ClassPath.NAVIGATOR to 100),
                ),
            )

            assertEquals(0, modifier.attackBonus)
            assertEquals(0, modifier.damageReduction)
        }

        test("secondary combat mastery does not replace the primary class combat identity") {
            val modifier = modifierFor(
                ClassMasteryState(
                    primaryClass = ClassPath.NAVIGATOR,
                    levels = mapOf(
                        ClassPath.NAVIGATOR to 20,
                        ClassPath.SWORDSMAN to 50,
                    ),
                ),
            )

            assertEquals(0, modifier.attackBonus)
            assertEquals(0, modifier.damageReduction)
        }

        test("combat mastery keeps scaling slowly beyond master milestone") {
            val modifier = modifierFor(
                ClassMasteryState(
                    primaryClass = ClassPath.SWORDSMAN,
                    levels = mapOf(ClassPath.SWORDSMAN to 1000),
                ),
            )

            assertEquals(23, modifier.attackBonus)
        }
    }

    private fun modifierFor(mastery: ClassMasteryState): CombatModifiers {
        val base = (CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success).profile
        val profile = base.copy(classMastery = mastery)
        val world = WorldState(
            campaignId = "class-combat",
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
            ),
        )
        return CombatModifierResolver.forWorld(world).getValue("p1")
    }
}
