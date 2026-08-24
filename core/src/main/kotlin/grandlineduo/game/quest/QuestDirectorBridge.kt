package grandlineduo.game.quest

import grandlineduo.core.model.WorldState
import grandlineduo.game.director.DirectorContext
import grandlineduo.game.director.DirectorDifficulty
import grandlineduo.game.director.GrandLineDirector
import java.util.Random

object QuestDirectorBridge {
    private data class Archetype(
        val id: String,
        val type: QuestType,
        val title: String,
        val issuerFaction: String,
        val targetId: String,
        val baseRequiredAmount: Int,
    )

    private val catalog = listOf(
        Archetype("dock-hunt", QuestType.HUNT, "Caçada aos saqueadores do cais", "BOUNTY_HUNTERS", "dock-raiders", 3),
        Archetype("charted-ruins", QuestType.EXPLORE, "Cartografar ruínas esquecidas", "CIVILIANS", "forgotten-ruins", 3),
        Archetype("medical-cache", QuestType.COLLECT, "Reunir suprimentos de emergência", "CIVILIANS", "medical-supplies", 4),
        Archetype("captured-sailor", QuestType.RESCUE, "Resgatar marinheiro capturado", "CIVILIANS", "captured-sailor", 1),
        Archetype("merchant-passage", QuestType.ESCORT, "Escoltar uma passagem mercante", "MERCHANTS", "merchant-convoy", 2),
        Archetype("underworld-ledger", QuestType.INVESTIGATE, "Investigar o livro-caixa clandestino", "UNDERWORLD", "smuggler-ledger", 3),
        Archetype("island-enforcer", QuestType.BOSS, "Derrubar o executor da ilha", "CIVILIANS", "island-enforcer", 1),
    )

    fun refresh(
        world: WorldState,
        seed: Long,
        difficulty: DirectorDifficulty,
        presentFactions: Set<String>,
    ): WorldState {
        val generation = world.questBoard.generationIndex
        val factions = presentFactions.toSortedSet()
        val enabledFlags = world.worldFlags
            .filterValues { it.isNotBlank() && it != "0" && !it.equals("false", ignoreCase = true) }
            .keys
            .toSortedSet()
        val currentHp = world.players.values.sumOf { it.hp }
        val maxHp = world.players.values.sumOf { it.maxHp }
        val totalBounty = world.players.values.sumOf { it.bounty }
        val budget = GrandLineDirector().threatBudget(
            DirectorContext(
                seed = seed,
                decisionIndex = generation,
                islandId = world.islandId,
                difficulty = difficulty,
                totalBounty = totalBounty,
                currentPartyHp = currentHp,
                maxPartyHp = maxHp,
                presentFactions = factions,
                worldFlags = enabledFlags,
                recentEventIds = emptyList(),
            )
        )
        val ceiling = rarityCeiling(budget)
        val randomSeed = seed xor
            (generation * 0x9E3779B97F4A7C15UL.toLong()) xor
            world.islandId.hashCode().toLong() xor
            factions.joinToString("|").hashCode().toLong()
        val random = Random(randomSeed)
        val start = random.nextInt(catalog.size)
        val ordered = List(catalog.size) { offset -> catalog[(start + offset) % catalog.size] }
        val resolved = world.questBoard.completedQuestIds + world.questBoard.failedQuestIds

        val generated = ordered.mapIndexed { slot, archetype ->
            val rarity = rarityForSlot(slot, ceiling)
            definitionFor(world, generation, slot, archetype, rarity, factions)
        }
        val offers = generated
            .filter { it.questId !in resolved }
            .take(3)
            .associateBy { it.questId }

        return world.copy(
            questBoard = world.questBoard.copy(
                generationIndex = generation + 1,
                offers = offers,
            ),
        )
    }

    private fun rarityCeiling(threatBudget: Int): QuestRarity = when {
        threatBudget <= 3 -> QuestRarity.COMMON
        threatBudget <= 6 -> QuestRarity.RARE
        threatBudget <= 9 -> QuestRarity.EPIC
        else -> QuestRarity.LEGENDARY
    }

    private fun rarityForSlot(slot: Int, ceiling: QuestRarity): QuestRarity {
        val desired = when (slot) {
            0 -> QuestRarity.COMMON
            1 -> QuestRarity.RARE
            else -> ceiling
        }
        return QuestRarity.entries[minOf(desired.ordinal, ceiling.ordinal)]
    }

    private fun definitionFor(
        world: WorldState,
        generation: Long,
        slot: Int,
        archetype: Archetype,
        rarity: QuestRarity,
        presentFactions: Set<String>,
    ): QuestDefinition {
        val raritySlug = rarity.name.lowercase()
        val questId = "${world.islandId}-$generation-${archetype.id}-$slot-$raritySlug"
        val reward = rewardFor(rarity, archetype.type)
        val requirement = requirementFor(rarity, archetype, presentFactions)
        val amountMultiplier = when (rarity) {
            QuestRarity.COMMON -> 1
            QuestRarity.RARE -> 2
            QuestRarity.EPIC -> 3
            QuestRarity.LEGENDARY -> 4
        }
        return QuestDefinition(
            questId = questId,
            islandId = world.islandId,
            title = when (rarity) {
                QuestRarity.COMMON -> archetype.title
                QuestRarity.RARE -> "Contrato raro: ${archetype.title}"
                QuestRarity.EPIC -> "Contrato épico: ${archetype.title}"
                QuestRarity.LEGENDARY -> "Contrato lendário: ${archetype.title}"
            },
            type = archetype.type,
            rarity = rarity,
            issuerFaction = issuerFor(archetype.issuerFaction, presentFactions),
            targetId = archetype.targetId,
            requiredAmount = archetype.baseRequiredAmount * amountMultiplier,
            requirement = requirement,
            reward = reward,
            expiresAfterGeneration = generation + when (rarity) {
                QuestRarity.COMMON -> 2
                QuestRarity.RARE -> 3
                QuestRarity.EPIC -> 4
                QuestRarity.LEGENDARY -> 6
            },
        )
    }

    private fun issuerFor(preferred: String, presentFactions: Set<String>): String = when {
        preferred in presentFactions -> preferred
        "CIVILIANS" in presentFactions -> "CIVILIANS"
        presentFactions.isNotEmpty() -> presentFactions.first()
        else -> preferred
    }

    private fun requirementFor(
        rarity: QuestRarity,
        archetype: Archetype,
        presentFactions: Set<String>,
    ): QuestRequirement {
        val minimumBounty = when (rarity) {
            QuestRarity.COMMON -> 0L
            QuestRarity.RARE -> 2_000_000L
            QuestRarity.EPIC -> 10_000_000L
            QuestRarity.LEGENDARY -> 30_000_000L
        }
        val factionRequirement = if (
            archetype.type == QuestType.INVESTIGATE && "UNDERWORLD" in presentFactions
        ) "UNDERWORLD" else null
        return QuestRequirement(
            factionId = factionRequirement,
            minimumFactionStanding = factionRequirement?.let { -20 },
            minimumTotalBounty = minimumBounty,
        )
    }

    private fun rewardFor(rarity: QuestRarity, type: QuestType): QuestReward {
        val berries = when (rarity) {
            QuestRarity.COMMON -> 800L
            QuestRarity.RARE -> 2_500L
            QuestRarity.EPIC -> 8_000L
            QuestRarity.LEGENDARY -> 25_000L
        }
        val evolution = when (rarity) {
            QuestRarity.COMMON -> 1
            QuestRarity.RARE -> 2
            QuestRarity.EPIC -> 4
            QuestRarity.LEGENDARY -> 8
        }
        val itemId = when {
            rarity == QuestRarity.LEGENDARY -> "kairouseki_shard"
            rarity == QuestRarity.EPIC -> "energy_tonic"
            type == QuestType.RESCUE -> "bandage"
            else -> null
        }
        return QuestReward(
            berries = berries,
            evolutionPoints = evolution,
            itemId = itemId,
            itemAmount = if (itemId == null) 0 else 1,
            worldFlag = if (rarity == QuestRarity.LEGENDARY) "QUEST_LEGENDARY_COMPLETED" else null,
        )
    }
}
