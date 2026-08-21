package grandlineduo.appshell

import grandlineduo.core.model.WorldState
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcEngine
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.duel.DuelFinishReason
import grandlineduo.game.duel.DuelPhase
import grandlineduo.game.duel.DuelState
import grandlineduo.game.quest.QuestDefinition
import grandlineduo.game.quest.QuestProgress
import grandlineduo.game.quest.QuestStatus
import grandlineduo.game.quest.QuestType
import grandlineduo.game.scenario.StormglassCayScenario
import grandlineduo.game.powers.PowerTechniqueEngine
import grandlineduo.game.ship.VoyageAction

enum class GameScreen {
    CHARACTER_CREATION,
    WAITING_FOR_PARTNER,
    STORY,
    ARC,
    COMBAT,
    DUEL,
    VOYAGE,
    HUB,
    QUESTS,
    END,
    GAME_OVER,
}

data class GameAction(val id: String, val label: String, val kind: String)

data class GamePresentation(
    val screen: GameScreen,
    val title: String,
    val body: String,
    val status: List<String> = emptyList(),
    val actions: List<GameAction> = emptyList(),
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
        val restored = StormglassPersistenceAdapter.decode(world)
        if (restored.combat?.status == CombatStatus.DEFEAT) {
            return GamePresentation(GameScreen.GAME_OVER, "Tripulação derrotada", "Capitão Veyron encerrou esta jornada.", statusFor(world, actorId))
        }

        world.activeDuel?.let { return duelPresentation(world, actorId, it) }
        if (activeCombat != null) return combatPresentation(world, actorId, activeCombat)
        restored.combat?.let { return combatPresentation(world, actorId, it) }

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
            if (arc.phase == ArcPhase.COMPLETE) return hub(world, actorId, "O conflito desta ilha terminou. Reorganize a tripulação antes de zarpar.")
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
            return hub(world, actorId, "Stormglass Cay ficou para trás. O Log Pose aponta para águas desconhecidas.")
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

    fun presentQuests(world: WorldState, actorId: String): GamePresentation {
        require(actorId == "p1" || actorId == "p2") { "Unknown actor" }
        val board = world.questBoard
        val body = buildString {
            append("Quadro da geração ${board.generationIndex} em ${world.islandId.replace('-', ' ')}.")
            append("\n\nOFERTAS")
            if (board.offers.isEmpty()) append("\nNenhum contrato disponível. Atualize o quadro para procurar novas oportunidades.")
            board.offers.toSortedMap().values.forEach { quest ->
                append("\n\n")
                append(questLine(quest))
                append("\nRecompensa: ").append(rewardLabel(quest))
            }
            append("\n\nATIVOS")
            if (board.active.isEmpty()) append("\nNenhum contrato aceito.")
            board.active.toSortedMap().values.forEach { progress ->
                append("\n\n")
                append(progressLine(progress))
                append("\nRecompensa: ").append(rewardLabel(progress.definition))
            }
            if (board.completedQuestIds.isNotEmpty() || board.failedQuestIds.isNotEmpty()) {
                append("\n\nHISTÓRICO • concluídos ${board.completedQuestIds.size} • falhos ${board.failedQuestIds.size}")
            }
        }
        val actions = buildList {
            add(GameAction("REFRESH", "Atualizar quadro de contratos", "QUEST"))
            board.offers.toSortedMap().values.forEach { quest ->
                add(GameAction("ACCEPT|${quest.questId}|1", "Aceitar • ${quest.title}", "QUEST"))
            }
            board.active.toSortedMap().values.forEach { progress ->
                when (progress.status) {
                    QuestStatus.ACTIVE -> {
                        if (progress.definition.type == QuestType.BOSS) {
                            if (world.activeCombat == null) add(GameAction("START_BOSS|${progress.definition.questId}|1", "Enfrentar alvo • ${progress.definition.title}", "QUEST"))
                        } else {
                            add(GameAction("PROGRESS|${progress.definition.questId}|1", "Registrar progresso • ${progress.definition.title}", "QUEST"))
                        }
                    }
                    QuestStatus.READY_TO_TURN_IN -> add(GameAction("TURN_IN|${progress.definition.questId}|1", "Entregar contrato • ${progress.definition.title}", "QUEST"))
                    else -> Unit
                }
            }
        }
        return GamePresentation(GameScreen.QUESTS, "CONTRATOS DA ILHA", body, statusFor(world, actorId), actions)
    }

    private fun duelPresentation(world: WorldState, actorId: String, duel: DuelState): GamePresentation {
        val opponentId = if (actorId == "p1") "p2" else "p1"
        val actorName = world.players[actorId]?.name ?: actorId.uppercase()
        val opponentName = world.players[opponentId]?.name ?: opponentId.uppercase()
        return when (duel.phase) {
            DuelPhase.PENDING -> if (actorId == duel.challengerId) {
                GamePresentation(
                    GameScreen.WAITING_FOR_PARTNER,
                    "Desafio enviado",
                    "Aguardando $opponentName aceitar ou recusar o duelo.",
                    statusFor(world, actorId),
                )
            } else {
                GamePresentation(
                    GameScreen.DUEL,
                    "Desafio de duelo",
                    "$opponentName desafiou você para um duelo não letal. O duelo só começa com seu consentimento.",
                    statusFor(world, actorId),
                    listOf(
                        GameAction("ACCEPT", "Aceitar duelo", "DUEL"),
                        GameAction("DECLINE", "Recusar duelo", "DUEL"),
                    ),
                )
            }
            DuelPhase.ACTIVE -> {
                val actorFighter = duel.fighters[actorId]
                val opponentFighter = duel.fighters[opponentId]
                val actorLocked = actorId in duel.lockedActions
                val opponentReady = opponentId in duel.lockedActions
                val body = buildString {
                    append("Rodada ${duel.round}\n")
                    append("$actorName: ${actorFighter?.hp ?: 0}/${actorFighter?.maxHp ?: 0} PV")
                    append(" • $opponentName: ${opponentFighter?.hp ?: 0}/${opponentFighter?.maxHp ?: 0} PV\n")
                    append(if (actorLocked) "Você pronto" else "Sua ação pendente")
                    append(" • ")
                    append(if (opponentReady) "Oponente pronto" else "Oponente escolhendo")
                }
                GamePresentation(
                    screen = if (actorLocked) GameScreen.WAITING_FOR_PARTNER else GameScreen.DUEL,
                    title = "Duelo • Rodada ${duel.round}",
                    body = body,
                    status = statusFor(world, actorId),
                    actions = if (actorLocked) emptyList() else buildList {
                        BASIC_COMBAT_ACTIONS.forEach { add(GameAction(it.name, combatLabel(it), "COMBAT")) }
                        val energy = world.players[actorId]?.energy ?: 0
                        PowerTechniqueEngine.available(world, actorId)
                            .filter { energy >= it.energyCost }
                            .forEach { add(GameAction(it.id, "${it.label} • ${it.energyCost} PE", "POWER")) }
                    },
                )
            }
            DuelPhase.FINISHED -> {
                val outcome = when (duel.finishReason) {
                    DuelFinishReason.DOUBLE_KNOCKOUT -> "Empate — nocaute duplo"
                    DuelFinishReason.KNOCKOUT -> {
                        val winnerName = duel.winnerId?.let { duel.fighters[it]?.name ?: world.players[it]?.name ?: it.uppercase() } ?: "Vencedor"
                        val loserName = duel.loserId?.let { duel.fighters[it]?.name ?: world.players[it]?.name ?: it.uppercase() } ?: "adversário"
                        "$winnerName venceu por nocaute sobre $loserName."
                    }
                    null -> "Duelo encerrado."
                }
                val p1 = duel.fighters["p1"]
                val p2 = duel.fighters["p2"]
                GamePresentation(
                    GameScreen.DUEL,
                    "Duelo encerrado",
                    buildString {
                        append(outcome)
                        if (p1 != null && p2 != null) {
                            append("\n${p1.name}: ${p1.hp}/${p1.maxHp} PV")
                            append(" • ${p2.name}: ${p2.hp}/${p2.maxHp} PV")
                        }
                    },
                    statusFor(world, actorId),
                    listOf(GameAction("CLOSE", "Encerrar duelo", "DUEL")),
                )
            }
        }
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
        val actions = buildList {
            if (actorId == "p1") add(GameAction("SAIL", "Zarpar para a próxima ilha", "CAMPAIGN"))
            if (world.worldFlags["campaign.mode"] == "HOST_COOP") add(GameAction("CHALLENGE", "Desafiar para duelo", "DUEL"))
            add(GameAction("QUESTS", "Contratos da ilha", "MENU"))
            add(GameAction("INVENTORY", "Inventário e equipamento", "MENU"))
            add(GameAction("SHOP", "Mercado da ilha", "MENU"))
            add(GameAction("SHIP", "Navio e suprimentos", "MENU"))
            add(GameAction("CREW", "Tripulação", "MENU"))
            add(GameAction("TRAINING", "Poderes e treino", "MENU"))
        }
        return GamePresentation(GameScreen.HUB, world.shipState?.name ?: "Tripulação", body, statusFor(world, actorId), actions)
    }

    private fun questLine(quest: QuestDefinition): String =
        "[${quest.rarity.name}] ${quest.title} • ${quest.type.name} • alvo ${quest.requiredAmount}"

    private fun progressLine(progress: QuestProgress): String =
        "[${progress.definition.rarity.name}] ${progress.definition.title} • ${progress.progress}/${progress.definition.requiredAmount} • ${progress.status.name.replace('_', ' ')}"

    private fun rewardLabel(quest: QuestDefinition): String = buildList {
        if (quest.reward.berries > 0) add("${quest.reward.berries} Berries")
        if (quest.reward.evolutionPoints > 0) add("${quest.reward.evolutionPoints} PEV")
        if (quest.reward.itemId != null && quest.reward.itemAmount > 0) add("${quest.reward.itemAmount}× ${quest.reward.itemId}")
        if (quest.reward.factionId != null && quest.reward.factionStandingDelta != 0) add("${quest.reward.factionStandingDelta} ${quest.reward.factionId}")
        if (quest.reward.worldFlag != null) add("marco ${quest.reward.worldFlag}")
    }.ifEmpty { listOf("sem recompensa material") }.joinToString(" • ")

    private fun statusFor(world: WorldState, actorId: String): List<String> {
        val p = world.players[actorId]
        val ship = world.shipState
        return buildList {
            if (p != null) {
                add("PV ${p.hp}/${p.maxHp} • PE ${p.energy}/${p.maxEnergy}")
                add("Recompensa ${p.bounty} Berries")
            }
            add("Caixa ${world.partyBerries} Berries")
            if (ship != null) add("Navio ${ship.hull}/${ship.maxHull} • Suprimentos ${ship.supplies}/${ship.maxSupplies}")
        }
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
