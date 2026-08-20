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
                return hub(world, actorId, "O conflito desta ilha terminou. Reorganize a tripulação antes de zarpar.")
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
            add(GameAction("INVENTORY", "Inventário e equipamento", "MENU"))
            add(GameAction("SHOP", "Mercado da ilha", "MENU"))
            add(GameAction("SHIP", "Navio e suprimentos", "MENU"))
            add(GameAction("CREW", "Tripulação", "MENU"))
            add(GameAction("TRAINING", "Poderes e treino", "MENU"))
        }
        return GamePresentation(
            GameScreen.HUB,
            world.shipState?.name ?: "Tripulação",
            body,
            statusFor(world, actorId),
            actions,
        )
    }

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
