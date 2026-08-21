package grandlineduo.game

import grandlineduo.core.model.WorldState
import grandlineduo.game.character.ClassMasteryMedicalResolver

enum class ItemType { WEAPON, ARMOR, CHARM, CONSUMABLE, MATERIAL, KEY }
enum class ItemRarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }
enum class EquipmentSlot { WEAPON, ARMOR, CHARM }

data class ItemDefinition(
    val id: String,
    val name: String,
    val type: ItemType,
    val description: String,
    val attackDamage: Int = 0,
    val damageReduction: Int = 0,
    val healHp: Int = 0,
    val restoreEnergy: Int = 0,
    val valueBerries: Long = 0,
    val rarity: ItemRarity = ItemRarity.COMMON,
)

data class InventoryState(
    val items: Map<String, Int>,
    val equipped: Map<EquipmentSlot, String>,
)

data class CombatItemBonus(val attackDamage: Int = 0, val damageReduction: Int = 0)

object ItemCatalog {
    const val FIELD_BOSS_LEGENDARY_ID = "horizon_cleaver"

    private val definitions = listOf(
        ItemDefinition("rusted_cutlass", "Sabre Gastado", ItemType.WEAPON, "Lâmina simples, confiável no início da rota.", attackDamage = 2, valueBerries = 800),
        ItemDefinition("iron_sabre", "Sabre de Ferro", ItemType.WEAPON, "Arma equilibrada para combate próximo.", attackDamage = 4, valueBerries = 2_800, rarity = ItemRarity.UNCOMMON),
        ItemDefinition("flintlock", "Pistola Flintlock", ItemType.WEAPON, "Disparo forte a curta distância.", attackDamage = 4, valueBerries = 3_200, rarity = ItemRarity.UNCOMMON),
        ItemDefinition("reinforced_coat", "Casaco Reforçado", ItemType.ARMOR, "Camadas costuradas para amortecer golpes.", damageReduction = 2, valueBerries = 2_400, rarity = ItemRarity.UNCOMMON),
        ItemDefinition("marine_vest", "Colete Naval", ItemType.ARMOR, "Proteção rígida recuperada de um depósito da Marinha.", damageReduction = 3, valueBerries = 4_800, rarity = ItemRarity.RARE),
        ItemDefinition("lucky_charm", "Amuleto de Maré", ItemType.CHARM, "Uma lembrança de porto. Pequena vantagem narrativa.", valueBerries = 1_500, rarity = ItemRarity.RARE),
        ItemDefinition("bandage", "Bandagem", ItemType.CONSUMABLE, "Recupera até 15 PV.", healHp = 15, valueBerries = 250),
        ItemDefinition("energy_tonic", "Tônico de Energia", ItemType.CONSUMABLE, "Recupera até 8 PE.", restoreEnergy = 8, valueBerries = 450, rarity = ItemRarity.UNCOMMON),
        ItemDefinition("ration", "Ração de Viagem", ItemType.CONSUMABLE, "Recupera até 8 PV e 3 PE.", healHp = 8, restoreEnergy = 3, valueBerries = 180),
        ItemDefinition("kairouseki_shard", "Fragmento de Kairouseki", ItemType.MATERIAL, "Material raro que enfraquece usuários de Akuma no Mi.", valueBerries = 8_000, rarity = ItemRarity.EPIC),
        ItemDefinition("stormglass_log_pose", "Log Pose de Stormglass", ItemType.KEY, "Abre a rota para as ilhas seguintes.", valueBerries = 0, rarity = ItemRarity.RARE),
        ItemDefinition(
            id = FIELD_BOSS_LEGENDARY_ID,
            name = "Lâmina Quebra-Horizonte",
            type = ItemType.WEAPON,
            description = "Relíquia tomada de um capitão de caça da Grand Line. Seu fio pesado atravessa guardas que armas comuns não vencem.",
            attackDamage = 8,
            valueBerries = 50_000,
            rarity = ItemRarity.LEGENDARY,
        ),
    ).associateBy { it.id }

    fun get(id: String): ItemDefinition = definitions[id] ?: throw IllegalArgumentException("Unknown item $id")
    fun all(): List<ItemDefinition> = definitions.values.sortedBy { it.name }
}

object InventoryEngine {
    private fun itemKey(playerId: String, itemId: String) = "inv.$playerId.$itemId"
    private fun equipKey(playerId: String, slot: EquipmentSlot) = "equip.$playerId.${slot.name}"

    fun read(world: WorldState, playerId: String): InventoryState {
        requirePlayer(world, playerId)
        val prefix = "inv.$playerId."
        val items = world.worldFlags.entries
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { (key, raw) -> raw.toIntOrNull()?.takeIf { it > 0 }?.let { key.removePrefix(prefix) to it } }
            .toMap()
        val equipped = EquipmentSlot.entries.mapNotNull { slot ->
            world.worldFlags[equipKey(playerId, slot)]?.takeIf { it.isNotBlank() }?.let { slot to it }
        }.toMap()
        return InventoryState(items, equipped)
    }

    fun grant(world: WorldState, playerId: String, itemId: String, amount: Int): WorldState {
        require(amount > 0) { "Amount must be positive" }
        requirePlayer(world, playerId)
        ItemCatalog.get(itemId)
        val flags = world.worldFlags.toMutableMap()
        val key = itemKey(playerId, itemId)
        val current = flags[key]?.toIntOrNull() ?: 0
        flags[key] = (current + amount).toString()
        return world.copy(worldFlags = flags)
    }

    fun equip(world: WorldState, playerId: String, itemId: String): WorldState {
        val inventory = read(world, playerId)
        require((inventory.items[itemId] ?: 0) > 0) { "Item is not in inventory" }
        val item = ItemCatalog.get(itemId)
        val slot = when (item.type) {
            ItemType.WEAPON -> EquipmentSlot.WEAPON
            ItemType.ARMOR -> EquipmentSlot.ARMOR
            ItemType.CHARM -> EquipmentSlot.CHARM
            else -> throw IllegalArgumentException("${item.name} cannot be equipped")
        }
        return world.copy(worldFlags = world.worldFlags + (equipKey(playerId, slot) to itemId))
    }

    fun unequip(world: WorldState, playerId: String, slot: EquipmentSlot): WorldState {
        requirePlayer(world, playerId)
        val flags = world.worldFlags.toMutableMap()
        flags.remove(equipKey(playerId, slot))
        return world.copy(worldFlags = flags)
    }

    fun discard(world: WorldState, playerId: String, itemId: String, amount: Int): WorldState {
        require(amount > 0) { "Amount must be positive" }
        val inventory = read(world, playerId)
        require(itemId !in inventory.equipped.values) { "Unequip item before discarding it" }
        val current = inventory.items[itemId] ?: 0
        require(current >= amount) { "Not enough items" }
        val flags = world.worldFlags.toMutableMap()
        val key = itemKey(playerId, itemId)
        val next = current - amount
        if (next == 0) flags.remove(key) else flags[key] = next.toString()
        return world.copy(worldFlags = flags)
    }

    fun use(world: WorldState, playerId: String, itemId: String): WorldState {
        val inventory = read(world, playerId)
        require((inventory.items[itemId] ?: 0) > 0) { "Item is not in inventory" }
        val item = ItemCatalog.get(itemId)
        require(item.type == ItemType.CONSUMABLE) { "${item.name} is not consumable" }
        val player = world.players.getValue(playerId)
        val healingBonus = ClassMasteryMedicalResolver.healingBonus(player.profile, item.healHp)
        val updated = player.copy(
            hp = (player.hp + item.healHp + healingBonus).coerceAtMost(player.maxHp),
            energy = (player.energy + item.restoreEnergy).coerceAtMost(player.maxEnergy),
        )
        val flags = world.worldFlags.toMutableMap()
        val key = itemKey(playerId, itemId)
        val next = inventory.items.getValue(itemId) - 1
        if (next == 0) flags.remove(key) else flags[key] = next.toString()
        return world.copy(players = world.players + (playerId to updated), worldFlags = flags)
    }

    fun combatBonus(world: WorldState, playerId: String): CombatItemBonus {
        val inv = read(world, playerId)
        val weapon = inv.equipped[EquipmentSlot.WEAPON]?.let(ItemCatalog::get)
        val armor = inv.equipped[EquipmentSlot.ARMOR]?.let(ItemCatalog::get)
        return CombatItemBonus(
            attackDamage = weapon?.attackDamage ?: 0,
            damageReduction = armor?.damageReduction ?: 0,
        )
    }

    fun grantStarterKit(world: WorldState, playerId: String, combatStyle: String): WorldState {
        var next = grant(world, playerId, if (combatStyle.contains("tiro", true) || combatStyle.contains("pistol", true)) "flintlock" else "rusted_cutlass", 1)
        next = grant(next, playerId, "bandage", 2)
        next = grant(next, playerId, "ration", 2)
        val weapon = read(next, playerId).items.keys.first { ItemCatalog.get(it).type == ItemType.WEAPON }
        return equip(next, playerId, weapon)
    }

    private fun requirePlayer(world: WorldState, playerId: String) {
        require(playerId in world.players) { "Unknown player $playerId" }
    }
}
