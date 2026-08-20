package grandlineduo.appshell

import grandlineduo.core.model.WorldState
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcEngine
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.scenario.StormglassCayScenario
import grandlineduo.game.powers.PowerTechniqueEngine
import grandlineduo.game.pvp.TrainingDuelAction
import grandlineduo.game.pvp.TrainingDuelEngine
import grandlineduo.game.pvp.TrainingDuelStatus
import grandlineduo.game.ship.VoyageAction
import grandlineduo.game.world.ExplorationCombatEngine
import grandlineduo.game.world.ExplorationDirection
import grandlineduo.game.world.ExplorationEnemyRank
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
    val playerPositions: Map<String, GridPosition> = emptyMap(),
    val interaction: ExplorationInteraction?,
    val visibleQuestObjectives: Set<GridPosition> = emptySet(),
    val visiblePickups: Set<GridPosition> = emptySet(),
    val visibleEnemies: Set<GridPosition> = emptySet(),
    val trackedBossHuntTarget: GridPosition? = null,
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
        require(actorId in HUMAN_PLAYER_IDS) { "Unknown actor" }
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
        if (actorId !in LEGACY_DECISION_PLAYER_IDS && world.activeArc?.phase != null && world.activeArc?.phase != ArcPhase.COMPLETE) {
            return legacyObserverPresentation(world, actorId, "o arco narrativo")
        }
        val activeCombat = world.activeCombat
        if (activeCombat?.status == CombatStatus.DEFEAT) {
            return GamePresentation(GameScreen.GAME_OVER, "Tripulação derrotada", "A campanha terminou aqui. O modo hardcore não oferece proteção narrativa.", statusFor(world, actorId))
        }
        if (activeCombat != null) return combatPresentation(world, actorId, activeCombat)

        val restored = StormglassPersistenceAdapter.decode(world)
        if (actorId !in LEGACY_DECISION_PLAYER_IDS && restored.scenario.stage != grandlineduo.game.scenario.ScenarioStage.COMPLETE) {
            return legacyObserverPresentation(world, actorId, "a narrativa de Stormglass")
        }
        restored.combat?.let { combat ->
            if (combat.status == CombatStatus.DEFEAT) {
                return GamePresentation(GameScreen.GAME_OVER, "Tripulação derrotada", "Capitão Veyron encerrou esta jornada.", statusFor(world, actorId))
            }
            return combatPresentation(world, actorId, combat)
        }

        world.activeVoyage?.let { voyage ->
            if (actorId !in LEGACY_DECISION_PLAYER_IDS) {
                return legacyObserverPresentation(world, actorId, "o incidente de viagem")
            }
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
            if (actorId !in LEGACY_DECISION_PLAYER_IDS) {
                return legacyObserverPresentation(world, actorId, "o arco narrativo")
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

    private fun legacyObserverPresentation(world: WorldState, actorId: String, context: String): GamePresentation =
        GamePresentation(
            GameScreen.WAITING_FOR_PARTNER,
            "Observando $context",
            "P1 e P2 estão resolvendo esta fase legada de duas pessoas. Você continua sincronizado e volta à exploração quando ela terminar.",
            statusFor(world, actorId),
            emptyList(),
        )

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
        val partnerId = if (actorId == "p1") "p2" else "p1"
        val partner = world.players[partnerId]
        val partnerInteraction = ExplorationEngine.interactionAt(world, partnerId)
        val duel = TrainingDuelEngine.state(world)
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
        val visibleEnemies = map.enemies.values
            .filterNot { ExplorationCombatEngine.isDefeated(world, it.id) }
            .map { it.position }
            .toSet()
        val fieldBoss = map.enemies.values.firstOrNull { it.rank == ExplorationEnemyRank.FIELD_BOSS }
        val bossHuntQuestId = fieldBoss
            ?.let { ExplorationQuestEngine.bossHuntQuestId(world.islandId) }
            ?.takeIf { id -> map.npcs.values.any { it.questId == id } }
        val bossHuntStatus = bossHuntQuestId?.let { ExplorationQuestEngine.status(world, actorId, it) }
        val trackedBossHuntTarget = fieldBoss?.takeIf { boss ->
            bossHuntStatus == ExplorationQuestStatus.ACTIVE && !ExplorationCombatEngine.isDefeated(world, boss.id)
        }?.position
        val bossHuntIntel = when (bossHuntStatus) {
            ExplorationQuestStatus.ACTIVE ->
                "CAÇADA ATIVA • ${fieldBoss?.name ?: "Chefe de campo"} • derrote o field boss e volte a Rook."
            ExplorationQuestStatus.OBJECTIVE_COMPLETE ->
                "CAÇADA CONCLUÍDA • ${fieldBoss?.name ?: "O chefe de campo"} caiu • volte a Rook para receber ${ExplorationQuestEngine.BOSS_REWARD_BERRIES} Berries + recompensa de caça."
            ExplorationQuestStatus.AVAILABLE, ExplorationQuestStatus.TURNED_IN, null -> null
        }
        val fieldBossIntel = fieldBoss?.let { boss ->
            val remaining = ExplorationCombatEngine.stepsUntilRespawn(world, boss.id)
            val difficulty = "dificuldade ${boss.difficulty.name.lowercase()}"
            when {
                remaining == Int.MAX_VALUE ->
                    "CHEFE DE CAMPO • ${boss.name} • $difficulty • derrotado permanentemente • ${boss.maxHp} PV • ataque ${boss.attackPower} • recompensa ${boss.rewardBerries} Berries + ${boss.rewardMasteryExperience} XP"
                remaining > 0 ->
                    "CHEFE DE CAMPO • ${boss.name} • $difficulty • derrotado • reaparece em $remaining passos • ${boss.maxHp} PV • ataque ${boss.attackPower} • recompensa ${boss.rewardBerries} Berries + ${boss.rewardMasteryExperience} XP"
                else -> {
                    val firstClearOffer = if (!ExplorationCombatEngine.hasClaimedFirstClear(world, boss.id)) {
                        " • PRIMEIRA VITÓRIA 2X • Berries + XP"
                    } else {
                        ""
                    }
                    "CHEFE DE CAMPO • ${boss.name} • $difficulty • ATIVO • ${boss.maxHp} PV • ataque ${boss.attackPower} • recompensa ${boss.rewardBerries} Berries + ${boss.rewardMasteryExperience} XP • respawn ${boss.respawnSteps} passos após vitória$firstClearOffer"
                }
            }
        }
        val duelContext = when (duel?.status) {
            TrainingDuelStatus.CHALLENGED -> if (actorId == duel.challengerId) {
                "DUELO DE TREINO • desafio enviado a ${partner?.name ?: partnerId}. Aguardando resposta na arena."
            } else {
                "DUELO DE TREINO • ${partner?.name ?: partnerId} desafiou você. Aceite ou recuse o combate não letal."
            }
            TrainingDuelStatus.ACTIVE -> {
                val ownHp = duel.duelHp[actorId] ?: 0
                val rivalHp = duel.duelHp[partnerId] ?: 0
                val waiting = if (actorId in duel.lockedActions) {
                    " Sua ação está travada. Aguardando o rival."
                } else {
                    " Escolha sua ação; a rodada resolve quando ambos decidirem."
                }
                "DUELO • rodada ${duel.round} • você $ownHp PV • rival $rivalHp PV.$waiting"
            }
            null -> null
        }

        val actions = buildList {
            if (duel != null) {
                when (duel.status) {
                    TrainingDuelStatus.CHALLENGED -> if (actorId == duel.opponentId) {
                        add(GameAction("", "Aceitar duelo", "DUEL_ACCEPT"))
                        add(GameAction("", "Recusar duelo", "DUEL_DECLINE"))
                    } else if (actorId == duel.challengerId) {
                        add(GameAction("", "Cancelar desafio", "DUEL_CANCEL"))
                    }
                    TrainingDuelStatus.ACTIVE -> {
                        if (actorId !in duel.lockedActions) {
                            TrainingDuelAction.entries.forEach { action ->
                                add(GameAction(action.name, duelActionLabel(action), "DUEL_ACTION"))
                            }
                        }
                        add(GameAction("", "Desistir do duelo", "DUEL_FORFEIT"))
                    }
                }
            } else {
                ExplorationDirection.entries.forEach { direction ->
                    add(GameAction(direction.name, explorationLabel(direction), "EXPLORE_MOVE"))
                }
                add(GameAction("INVENTORY", "Inventário e equipamento", "MENU"))
                add(GameAction("QUESTS", "Diário de missões", "MENU"))

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
                    ExplorationInteraction.TRAINING -> {
                        add(GameAction("TRAINING", "Treinar nesta área", "MENU"))
                        if (partnerInteraction == ExplorationInteraction.TRAINING) {
                            add(GameAction("", "Desafiar ${partner?.name ?: partnerId} para duelo", "DUEL_CHALLENGE"))
                        }
                    }
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
        }
        val island = GrandLineWorldAtlas.describe(world.campaignId, world.islandId)
        val physicalContext = when {
            npc != null -> {
                val isBossHunt = npc.questId?.let(ExplorationQuestEngine::isBossHuntQuest) == true
                when (questStatus) {
                    ExplorationQuestStatus.AVAILABLE -> "${npc.name}, ${npc.title}: ${npc.dialogue}"
                    ExplorationQuestStatus.ACTIVE -> if (isBossHunt) {
                        "${npc.name}: A caçada está ativa. Derrube ${fieldBoss?.name ?: "o chefe de campo"} e volte vivo."
                    } else {
                        "${npc.name}: A caixa está na estrada leste. Volte quando encontrá-la."
                    }
                    ExplorationQuestStatus.OBJECTIVE_COMPLETE -> if (isBossHunt) {
                        "${npc.name}: Você voltou vivo. Entregue o contrato para receber a recompensa."
                    } else {
                        "${npc.name}: Você encontrou? Traga a caixa para mim."
                    }
                    ExplorationQuestStatus.TURNED_IN -> if (isBossHunt) {
                        "${npc.name}: Contrato encerrado. Boa caçada."
                    } else {
                        "${npc.name}: Bom trabalho. A recompensa é sua."
                    }
                    null -> "${npc.name}, ${npc.title}."
                }
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
        val contextualBody = buildList {
            add(body)
            physicalContext?.let(::add)
            duelContext?.let(::add)
            bossHuntIntel?.let(::add)
            fieldBossIntel?.let(::add)
        }.joinToString("\n")
        return GamePresentation(
            screen = GameScreen.HUB,
            title = "${world.shipState?.name ?: "Tripulação"} • ${island.name}",
            body = contextualBody,
            status = statusFor(world, actorId),
            actions = actions,
            exploration = ExplorationPresentation(
                map = map,
                playerPosition = playerPosition,
                playerPositions = world.players.keys.sorted().associateWith { ExplorationEngine.position(world, it) },
                interaction = interaction,
                visibleQuestObjectives = activeQuestObjectives,
                visiblePickups = visiblePickups,
                visibleEnemies = visibleEnemies,
                trackedBossHuntTarget = trackedBossHuntTarget,
            ),
        )
    }

    private fun statusFor(world: WorldState, actorId: String): List<String> {
        val p = world.players[actorId]
        val ship = world.shipState
        val island = GrandLineWorldAtlas.describe(world.campaignId, world.islandId)
        val roster = world.players.values.filter { it.profile != null }.sortedBy { it.playerId }
        return buildList {
            if (roster.isNotEmpty()) {
                add("Tripulação ${roster.size}/4")
                roster.chunked(2).forEach { group ->
                    add(group.joinToString(" • ") { "${it.playerId.uppercase()} ${it.name}" })
                }
            }
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

    private fun duelActionLabel(action: TrainingDuelAction): String = when (action) {
        TrainingDuelAction.ATTACK -> "Atacar rival"
        TrainingDuelAction.DEFEND -> "Defender"
        TrainingDuelAction.DODGE -> "Esquivar"
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

    private val HUMAN_PLAYER_IDS = setOf("p1", "p2", "p3", "p4")
    private val LEGACY_DECISION_PLAYER_IDS = setOf("p1", "p2")

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