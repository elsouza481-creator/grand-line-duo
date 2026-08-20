package grandlineduo.game.combat

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.InventoryEngine
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
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
    }
}
