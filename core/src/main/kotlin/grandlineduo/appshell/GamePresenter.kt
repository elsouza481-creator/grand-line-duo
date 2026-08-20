package grandlineduo.appshell

import grandlineduo.core.model.WorldState
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcEngine
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.scenario.StormglassCayScenario
import grandlineduo.game.powers.PowerTechniqueEngine
import grandlineduo.game.ship.VoyageAction
import grandlineduo.game.world.ExplorationDirection
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.game.world.ExplorationLootEngine
import grandlineduo.game.world.ExplorationMap
import grandlineduo.game.world.ExplorationQuestEngine
import grandlineduo.game.world.ExplorationQuestStatus
import grandlineduo.game.world.GridPosition
import grandlineduo.game.world.GrandLineWorldAtlas

enum class GameScreen {
    CHARACTER_CREATION,
    WAITING_FOR_PARTNER,
    STORY,
    ARC,
    COMBAT,
    VOYAGE,
    HUB,
    END,
    GAME_OVER,
}

data class GameAction(val id: String, val label: String, val kind: String)

data class ExplorationPresentation(
    val map: ExplorationMap,
    val playerPosition: GridPosition,
    val interaction: ExplorationInteraction?,
    val visibleQuestObjectives: Set<GridPosition> = emptySet(),
    val visiblePickups: Set<GridPosition> = emptySet(),
)

data class GamePresentation(
    val screen: GameScreen,
    val title: String,
    val body: String,
    val status: List<String> = emptyList(),
    val actions: List<GameAction> = emptyList(),
    val exploration: ExplorationPresentation? = null,
)

object GamePresenter {
    fun present(world: WorldState, actorId: String): GamePresentation {
        require(actorId == "p1" || actorId == "p2") { "Unknown actor" }
        val actor = world.players[actorId] ?: return GamePresentation(
            GameScreen.CHARACTER_CREATION, "Criar personagem", "Prepare seu personagem para iniciar a aventura."
        )
        if (actor.profile == null) {
            return GamePresentation(
                GameScreen.CHARACTER_CREATION,
                "Seu personagem",
                "Escolha nome, origem, estilo de luta e aparência. As regras hardcore serão aplicadas à ficha.",
                statusFor(world, actorId),
            )
        }
        val partnerId = if (actorId == "p1") "p2" else "p1"
        if (world.players[partnerId]?.profile == null) {
            return GamePresentation(
                GameScreen.WAITING_FOR_PARTNER,
                "Aguardando tripulante",
                "Seu personagem está pronto. A campanha começa quando o outro tripulante concluir a própria ficha.",
                statusFor(world, actorId),
            )
        }
        if (world.worldFlags["campaign.complete"] == "true") {
            return GamePresentation(
                GameScreen.END,
                "A rota chegou ao fim",
                world.worldFlags["campaign.epilogue"] ?: "Sua tripulação deixou uma marca permanente na Grand Line.",
                statusFor(world, actorId),
            )
        }
        val activeCombat = world.activeCombat
        if (activeCombat?.status == CombatStatus.DEFEAT) {
            return GamePresentation(GameScreen.GAME_OVER, "Tripulação derrotada", "A campanha terminou aqui. O modo hardcore não oferece proteção narrativa.", statusFor(world, actorId))
        }
        if (activeCombat != null) return combatPresentation(world, actorId, activeCombat)

        val restored = StormglassPersistenceAdapter.decode(world)
        restored.combat?.let { combat ->
            if (combat.status == CombatStatus.DEFEAT) {
                return GamePresentation(GameScreen.GAME_OVER, "Tripulação derrotada", "Capitão Veyron encerrou esta jornada.", statusFor(world, actorId))
            }
            return combatPresentation(world, actorId, combat)
        }

        world.activeVoyage?.let { voyage ->
            val already = actorId in voyage.actions
            return GamePresentation(
                screen = if (already) GameScreen.WAITING_FOR_PARTNER else GameScreen.VOYAGE,
                title = "Incidente no mar: ${voyage.incident.type.name.replace('_', ' ')}",
                body = if (already) "Sua ação está travada. Aguardando a outra decisão." else "O mar virou contra o navio. Escolha sua função nesta janela tática.",
                status = statusFor(world, actorId),
                actions = if (already) emptyList() else VoyageAction.entries.map { GameAction(it.name, voyageLabel(it), "VOYAGE") },
            )
        }

        world.activeArc?.let { arc ->
            if (arc.phase == ArcPhase.COMPLETE) {
                return hub(world, actorId, "O conflito desta ilha terminou. Explore o porto, reabasteça e encontre o cais para escolher a próxima rota.")
            }
            if (actorId in arc.actedThisPhase) {
                return GamePresentation(GameScreen.WAITING_FOR_PARTNER, "Decisão registrada", "Aguardando a outra decisão para o Director resolver a cena.", statusFor(world, actorId))
            }
            val view = ArcEngine.view(arc, actorId)
            return GamePresentation(
                GameScreen.ARC,
                view.title,
                view.description,
                statusFor(world, actorId),
                view.choices.map { GameAction(it.id, it.label, "ARC") },
            )
        }

        if (restored.scenario.stage == grandlineduo.game.scenario.ScenarioStage.COMPLETE) {
            return hub(world, actorId, "Stormglass Cay ficou para trás. Caminhe pelo porto e alcance o cais para zarpar pela Grand Line.")
        }
        if (actorId in restored.scenario.actedThisStage) {
            return GamePresentation(GameScreen.WAITING_FOR_PARTNER, "Decisão registrada", "Aguardando a outra decisão.", statusFor(world, actorId))
        }
        val scenario = StormglassCayScenario().view(restored.scenario, actorId)
        return GamePresentation(
            GameScreen.STORY,
            scenario.title,
            scenario.description,
            statusFor(world, actorId),
            scenario.choices.map { GameAction(it.id, it.label, "SCENARIO") },
        )
    }

    private fun combatPresentation(world: WorldState, actorId: String, combat: grandlineduo.game.combat.CombatState): GamePresentation {
        val fighter = combat.players[actorId]
        val already = actorId in combat.lockedActions || fighter?.hp == 0
        val telegraph = if (combat.telegraph.targetPlayerId == actorId) {
            "ATENÇÃO: ${combat.enemy.name} prepara ${combat.telegraph.type.name.replace('_', ' ')} contra você."
        } else {
            "${combat.enemy.name} mira ${combat.telegraph.targetPlayerId.uppercase()}."
        }
        return GamePresentation(
            screen = if (already && combat.status == CombatStatus.ACTIVE) GameScreen.WAITING_FOR_PARTNER else GameScreen.COMBAT,
            title = "Combate • Rodada ${combat.round}",
            body = "$telegraph\nInimigo: ${combat.enemy.hp}/${combat.enemy.maxHp} PV",
            status = statusFor(world, actorId),
            actions = if (already) emptyList() else buildList {
                BASIC_COMBAT_ACTIONS.forEach { add(GameAction(it.name, combatLabel(it), "COMBAT")) }
                val energy = world.players[actorId]?.energy ?: 0
                PowerTechniqueEngine.available(world, actorId)
                    .filter { energy >= it.energyCost }
                    .forEach { add(GameAction(it.id, "${it.label} • ${it.energyCost} PE", "POWER")) }
            },
        )
    }

    private fun hub(world: WorldState, actorId: String, body: String): GamePresentation {
        val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
        val playerPosition = ExplorationEngine.position(world, actorId)
        val interaction = ExplorationEngine.interactionAt(world, actorId)
        val npc = map.npcs[playerPosition]
        val objective = map.questObjectives[playerPosition]
        val pickup = map.pickups[playerPosition]?.takeUnless { ExplorationLootEngine.isCollected(world, it.id) }
        val questId = npc?.questId ?: objective?.questId
        val questStatus = questId?.let { ExplorationQuestEngine.status(world, actorId, it) }
        val activeQuestObjectives = map.questObjectives.values
            .filter { ExplorationQuestEngine.status(world, actorId, it.questId) == ExplorationQuestStatus.ACTIVE }
            .map { it.position }
            .toSet()
        val visiblePickups = map.pickups.values
            .filterNot { ExplorationLootEngine.isCollected(world, it.id) }
            .map { it.position }
            .toSet()

        val actions = buildList {
            ExplorationDirection.entries.forEach { direction ->
                add(GameAction(direction.name, explorationLabel(direction), "EXPLORE_MOVE"))
            }
            add(GameAction("INVENTORY", "Inventário e equipamento", "MENU"))

            if (npc?.questId != null) {
                when (ExplorationQuestEngine.status(world, actorId, npc.questId)) {
                    ExplorationQuestStatus.AVAILABLE -> add(
                        GameAction(npc.questId, "Aceitar missão de ${npc.name}", "QUEST_ACCEPT")
                    )
                    ExplorationQuestStatus.OBJECTIVE_COMPLETE -> add(
                        GameAction(npc.questId, "Entregar missão a ${npc.name}", "QUEST_TURN_IN")
                    )
                    ExplorationQuestStatus.ACTIVE, ExplorationQuestStatus.TURNED_IN -> Unit
                }
            }
            if (objective != null && ExplorationQuestEngine.status(world, actorId, objective.questId) == ExplorationQuestStatus.ACTIVE) {
                add(GameAction(objective.questId, "Investigar: ${objective.label}", "QUEST_PROGRESS"))
            }
            if (pickup != null) {
                add(GameAction(pickup.id, "Coletar cache de suprimentos", "LOOT_COLLECT"))
            }

            when (interaction) {
                ExplorationInteraction.MARKET -> add(GameAction("SHOP", "Entrar no mercado", "MENU"))
                ExplorationInteraction.TRAINING -> add(GameAction("TRAINING", "Treinar nesta área", "MENU"))
                ExplorationInteraction.SHIP -> add(GameAction("SHIP", "Gerenciar o navio", "MENU"))
                ExplorationInteraction.CREW -> add(GameAction("CREW", "Falar com a tripulação", "MENU"))
                ExplorationInteraction.DOCK -> if (actorId == "p1") {
                    val voyageIndex = world.worldFlags["world.voyages"]?.toIntOrNull()
                        ?: world.worldFlags["campaign.chapter"]?.toIntOrNull()
                        ?: 0
                    GrandLineWorldAtlas.availableDestinations(world.campaignId, world.islandId, voyageIndex).forEach { island ->
                        add(
                            GameAction(
                                island.id,
                                "Zarpar: ${island.name} • perigo ${island.danger}/10 • ${island.climate}",
                                "CAMPAIGN",
                            )
                        )
                    }
                }
                null -> Unit
            }
        }
        val island = GrandLineWorldAtlas.describe(world.campaignId, world.islandId)
        val physicalContext = when {
            npc != null -> when (questStatus) {
                ExplorationQuestStatus.AVAILABLE -> "${npc.name}, ${npc.title}: ${npc.dialogue}"
                ExplorationQuestStatus.ACTIVE -> "${npc.name}: A caixa está na estrada leste. Volte quando encontrá-la."
                ExplorationQuestStatus.OBJECTIVE_COMPLETE -> "${npc.name}: Você encontrou? Traga a caixa para mim."
                ExplorationQuestStatus.TURNED_IN -> "${npc.name}: Bom trabalho. A recompensa é sua."
                null -> "${npc.name}, ${npc.title}."
            }
            objective != null && questStatus == ExplorationQuestStatus.ACTIVE -> "Você encontrou ${objective.label}. Examine o local para cumprir o objetivo."
            pickup != null -> "Você encontrou ${pickup.label}. Colete antes que outra tripulação chegue."
            interaction == ExplorationInteraction.DOCK -> "Você está no cais."
            interaction == ExplorationInteraction.MARKET -> "Bancas e mercadores cercam você."
            interaction == ExplorationInteraction.TRAINING -> "Esta área foi preparada para treino."
            interaction == ExplorationInteraction.SHIP -> "Seu navio está atracado aqui."
            interaction == ExplorationInteraction.CREW -> "A tripulação se reúne neste ponto."
            else -> null
        }
        val contextualBody = if (physicalContext == null) body else "$body\n$physicalContext"
        return GamePresentation(
            screen = GameScreen.HUB,
            title = "${world.shipState?.name ?: "Tripulação"} • ${island.name}",
            body = contextualBody,
            status = statusFor(world, actorId),
            actions = actions,
            exploration = ExplorationPresentation(
                map = map,
                playerPosition = playerPosition,
                interaction = interaction,
                visibleQuestObjectives = activeQuestObjectives,
                visiblePickups = visiblePickups,
            ),
        )
    }

    private fun statusFor(world: WorldState, actorId: String): List<String> {
        val p = world.players[actorId]
        val ship = world.shipState
        val island = GrandLineWorldAtlas.describe(world.campaignId, world.islandId)
        return buildList {
            if (p != null) {
                add("PV ${p.hp}/${p.maxHp} • PE ${p.energy}/${p.maxEnergy}")
                p.profile?.classMastery?.let { add(ClassPathDisplay.primaryProgress(it)) }
                add("Recompensa ${p.bounty} Berries")
            }
            add("Ilha ${island.name} • perigo ${island.danger}/10")
            add("Caixa ${world.partyBerries} Berries")
            if (ship != null) add("Navio ${ship.hull}/${ship.maxHull} • Suprimentos ${ship.supplies}/${ship.maxSupplies}")
        }
    }

    private fun explorationLabel(direction: ExplorationDirection): String = when (direction) {
        ExplorationDirection.NORTH -> "Mover ↑"
        ExplorationDirection.SOUTH -> "Mover ↓"
        ExplorationDirection.WEST -> "Mover ←"
        ExplorationDirection.EAST -> "Mover →"
    }

    private fun combatLabel(type: CombatActionType): String = when (type) {
        CombatActionType.ATTACK -> "Atacar"
        CombatActionType.DEFEND -> "Defender"
        CombatActionType.DODGE -> "Esquivar"
        CombatActionType.SETUP -> "Preparar abertura"
        CombatActionType.FINISHER -> "Finalizador"
        CombatActionType.HAKI_BUSOSHOKU -> "Busoshoku"
        CombatActionType.HAKI_KENBUNSHOKU -> "Kenbunshoku"
        CombatActionType.HAKI_HAOSHOKU -> "Haoshoku"
        CombatActionType.DEVIL_FRUIT -> "Akuma no Mi"
    }

    private val BASIC_COMBAT_ACTIONS = listOf(
        CombatActionType.ATTACK,
        CombatActionType.DEFEND,
        CombatActionType.DODGE,
        CombatActionType.SETUP,
        CombatActionType.FINISHER,
    )

    private fun voyageLabel(action: VoyageAction): String = when (action) {
        VoyageAction.HELM -> "Assumir o leme"
        VoyageAction.LOOKOUT -> "Vigiar a rota"
        VoyageAction.CANNONS -> "Preparar os canhões"
        VoyageAction.REPAIR -> "Reparar durante o incidente"
        VoyageAction.PROTECT_SUPPLIES -> "Proteger suprimentos"
    }
}
