package grandlineduo.game

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.character.ClassMasteryEngine
import grandlineduo.game.character.ClassPath
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object InventoryEngineTest {
    fun register() {
        test("inventory grants and equips a weapon deterministically") {
            val base = world()
            val granted = InventoryEngine.grant(base, "p1", "iron_sabre", 1)
            val equipped = InventoryEngine.equip(granted, "p1", "iron_sabre")
            val inv = InventoryEngine.read(equipped, "p1")
            assertEquals(1, inv.items.getValue("iron_sabre"))
            assertEquals("iron_sabre", inv.equipped[EquipmentSlot.WEAPON])
            assertEquals(4, InventoryEngine.combatBonus(equipped, "p1").attackDamage)
        }

        test("item catalog exposes meaningful rarity tiers without changing inventory identity") {
            assertEquals(ItemRarity.COMMON, ItemCatalog.get("ration").rarity)
            assertEquals(ItemRarity.UNCOMMON, ItemCatalog.get("iron_sabre").rarity)
            assertEquals(ItemRarity.RARE, ItemCatalog.get("marine_vest").rarity)
            assertEquals(ItemRarity.EPIC, ItemCatalog.get("kairouseki_shard").rarity)
            assertEquals(ItemRarity.LEGENDARY, ItemCatalog.get(ItemCatalog.FIELD_BOSS_LEGENDARY_ID).rarity)
        }

        test("field boss legendary weapon uses normal equipment persistence and grants real combat power") {
            var state = InventoryEngine.grant(world(), "p1", ItemCatalog.FIELD_BOSS_LEGENDARY_ID, 1)
            state = InventoryEngine.equip(state, "p1", ItemCatalog.FIELD_BOSS_LEGENDARY_ID)

            val inventory = InventoryEngine.read(state, "p1")
            val legendary = ItemCatalog.get(ItemCatalog.FIELD_BOSS_LEGENDARY_ID)
            assertEquals(ItemType.WEAPON, legendary.type)
            assertEquals(ItemRarity.LEGENDARY, legendary.rarity)
            assertEquals(ItemCatalog.FIELD_BOSS_LEGENDARY_ID, inventory.equipped[EquipmentSlot.WEAPON])
            assertTrue(InventoryEngine.combatBonus(state, "p1").attackDamage > ItemCatalog.get("iron_sabre").attackDamage)
        }

        test("consumable heals without exceeding max hp and consumes one") {
            var state = world().copy(players = world().players + ("p1" to world().players.getValue("p1").copy(hp = 7)))
            state = InventoryEngine.grant(state, "p1", "bandage", 2)
            state = InventoryEngine.use(state, "p1", "bandage")
            assertEquals(20, state.players.getValue("p1").hp)
            assertEquals(1, InventoryEngine.read(state, "p1").items.getValue("bandage"))
        }

        test("doctor primary mastery improves healing consumables by mastery tier") {
            val doctor = playerWithPrimaryClass(ClassPath.DOCTOR, level = 10).copy(hp = 1, maxHp = 30)
            var state = profiledWorld(doctor)
            state = InventoryEngine.grant(state, "p1", "bandage", 1)
            state = InventoryEngine.use(state, "p1", "bandage")
            assertEquals(24, state.players.getValue("p1").hp)
        }

        test("doctor mastery does not amplify pure energy consumables") {
            val doctor = playerWithPrimaryClass(ClassPath.DOCTOR, level = 25).copy(energy = 1, maxEnergy = 20)
            var state = profiledWorld(doctor)
            state = InventoryEngine.grant(state, "p1", "energy_tonic", 1)
            state = InventoryEngine.use(state, "p1", "energy_tonic")
            assertEquals(9, state.players.getValue("p1").energy)
        }

        test("secondary doctor mastery does not replace primary class medical identity") {
            val created = CharacterCreation.create(
                CharacterCreationTest.validDraft().copy(classPath = ClassPath.SWORDSMAN)
            ) as CharacterCreationResult.Success
            val secondaryDoctor = ClassMasteryEngine.train(
                created.profile.classMastery!!,
                ClassPath.DOCTOR,
                3_000,
            )
            val player = PlayerState(
                playerId = "p1",
                name = created.profile.name,
                hp = 1,
                maxHp = 30,
                bounty = 0,
                energy = 10,
                maxEnergy = 20,
                profile = created.profile.copy(classMastery = secondaryDoctor),
            )
            var state = profiledWorld(player)
            state = InventoryEngine.grant(state, "p1", "bandage", 1)
            state = InventoryEngine.use(state, "p1", "bandage")
            assertEquals(16, state.players.getValue("p1").hp)
        }

        test("equipped armor cannot be discarded until unequipped") {
            var state = InventoryEngine.grant(world(), "p1", "reinforced_coat", 1)
            state = InventoryEngine.equip(state, "p1", "reinforced_coat")
            var rejected = false
            try { InventoryEngine.discard(state, "p1", "reinforced_coat", 1) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            state = InventoryEngine.unequip(state, "p1", EquipmentSlot.ARMOR)
            state = InventoryEngine.discard(state, "p1", "reinforced_coat", 1)
            assertEquals(0, InventoryEngine.read(state, "p1").items["reinforced_coat"] ?: 0)
        }
    }

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

    private fun profiledWorld(player: PlayerState): WorldState = WorldState(
        campaignId = "inv-profiled",
        players = mapOf(
            "p1" to player,
            "p2" to PlayerState("p2", "P2", 20, 20, 0, 10, 10),
        ),
    )

    private fun world(): WorldState = WorldState(
        campaignId = "inv-test",
        players = mapOf(
            "p1" to PlayerState("p1", "P1", 20, 20, 0, 10, 10),
            "p2" to PlayerState("p2", "P2", 20, 20, 0, 10, 10),
        ),
    )
}
