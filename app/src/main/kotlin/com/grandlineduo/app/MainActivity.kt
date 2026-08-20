package com.grandlineduo.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.widget.Toast
import grandlineduo.appshell.GameAction
import grandlineduo.appshell.GamePresenter
import grandlineduo.appshell.GameScreen
import grandlineduo.appshell.GameSessionCoordinator
import grandlineduo.appshell.SessionMode
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.ship.VoyageAction
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val worker = ScheduledThreadPoolExecutor(2)
    private lateinit var saveRoot: Path
    private lateinit var coordinator: GameSessionCoordinator
    private var syncTask: ScheduledFuture<*>? = null
    private var currentOverlay: String? = null
    private var gameplayView: GameplayScreen? = null
    private var explorationView: ExplorationScreen? = null
    private var characterView: CharacterCreationScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        saveRoot = filesDir.toPath().resolve("campaigns")
        coordinator = GameSessionCoordinator(saveRoot)
        showHome()
    }

    private fun showHome(message: String = "Pronto para zarpar.") {
        stopSync()
        currentOverlay = null
        gameplayView = null
        explorationView = null
        characterView = null
        val home = HomeScreen(this).apply {
            onSolo = { startSolo() }
            onHost = { startHost() }
            onJoin = { joinCoop() }
            onContinue = { continueCampaign() }
            render(coordinator.savedCampaignIds().isNotEmpty(), message)
        }
        setContentView(home)
    }

    private fun startSolo() = runBusy("Criando campanha solo…") {
        coordinator.startSolo()
        postWorld()
    }

    private fun startHost() = runBusy("Abrindo campanha co-op…") {
        coordinator.startHost(Build.MODEL ?: "Android Host")
        postWorld()
        startSync()
    }

    private fun joinCoop() = runBusy("Procurando P1 na mesma rede…") {
        coordinator.discoverAndJoin(timeoutMillis = 8_000)
        postWorld()
        startSync()
    }

    private fun continueCampaign() = runBusy("Carregando campanha…") {
        val campaignId = coordinator.savedCampaignIds().lastOrNull()
            ?: throw IllegalStateException("Nenhum save local foi encontrado")
        coordinator.resume(campaignId)
        postWorld()
        if (coordinator.mode == SessionMode.HOST_COOP) startSync()
    }

    private fun runBusy(message: String, work: () -> Unit) {
        val home = HomeScreen(this).apply { render(coordinator.savedCampaignIds().isNotEmpty(), message, busy = true) }
        setContentView(home)
        worker.execute {
            try {
                work()
            } catch (t: Throwable) {
                runOnUiThread { showHome(t.message ?: "Não foi possível iniciar a sessão") }
            }
        }
    }

    private fun postWorld() {
        runOnUiThread { renderWorld() }
    }

    private fun renderWorld() {
        val world = runCatching { coordinator.worldState() }.getOrElse {
            showHome(it.message ?: "Sessão indisponível")
            return
        }
        if (currentOverlay != null) {
            renderOverlay(currentOverlay!!)
            return
        }
        val model = GamePresenter.present(world, coordinator.actorId)
        if (model.screen == GameScreen.CHARACTER_CREATION) {
            if (characterView != null) return
            val creator = CharacterCreationScreen(this).apply {
                onCreateCharacter = { draft ->
                    worker.execute {
                        try {
                            coordinator.createCharacter(draft)
                            postWorld()
                        } catch (t: Throwable) {
                            runOnUiThread { Toast.makeText(this@MainActivity, t.message ?: "Ficha inválida", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                onBack = { resetToHome() }
            }
            gameplayView = null
            explorationView = null
            characterView = creator
            setContentView(creator)
            return
        }
        characterView = null

        if (model.exploration != null) {
            gameplayView = null
            val view = explorationView ?: ExplorationScreen(this).also {
                explorationView = it
                it.onAction = { action -> dispatch(action) }
                it.onHome = { resetToHome() }
            }
            setContentView(view)
            view.render(model)
            return
        }

        explorationView = null
        val view = gameplayView ?: GameplayScreen(this).also {
            gameplayView = it
            it.onAction = { action -> dispatch(action) }
            it.onHome = { resetToHome() }
        }
        setContentView(view)
        view.render(model)
    }

    private fun dispatch(action: GameAction) {
        if (action.kind == "MENU") {
            currentOverlay = action.id
            renderOverlay(action.id)
            return
        }
        worker.execute {
            try {
                when (action.kind) {
                    "SCENARIO" -> coordinator.submitScenarioChoice(action.id)
                    "ARC" -> coordinator.submitArcChoice(action.id)
                    "COMBAT" -> coordinator.submitCombatAction(CombatActionType.valueOf(action.id))
                    "POWER" -> coordinator.submitPowerAction(action.id)
                    "VOYAGE" -> coordinator.submitVoyageAction(VoyageAction.valueOf(action.id))
                    "EXPLORE_MOVE" -> coordinator.submitWorldAction("EXPLORE_MOVE", action.id, 1)
                    "QUEST_ACCEPT", "QUEST_PROGRESS", "QUEST_TURN_IN", "LOOT_COLLECT" ->
                        coordinator.submitWorldAction(action.kind, action.id, 1)
                    "CAMPAIGN" -> coordinator.advanceCampaign(action.id)
                    else -> throw IllegalArgumentException("Ação não suportada: ${action.kind}")
                }
                postWorld()
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this@MainActivity, t.message ?: "Ação recusada", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun renderOverlay(id: String) {
        val world = runCatching { coordinator.worldState() }.getOrNull() ?: return
        when (id) {
            "INVENTORY" -> {
                val view = InventoryScreen(this).apply {
                    onBack = { closeOverlay() }
                    onInventoryAction = { action, target ->
                        worker.execute {
                            try {
                                coordinator.submitInventoryAction(action, target)
                                runOnUiThread { renderOverlay("INVENTORY") }
                            } catch (t: Throwable) {
                                runOnUiThread { Toast.makeText(this@MainActivity, t.message ?: "Ação recusada", Toast.LENGTH_LONG).show() }
                            }
                        }
                    }
                }
                view.render(world, coordinator.actorId)
                setContentView(view)
            }
            "QUESTS" -> {
                val view = QuestJournalScreen(this).apply {
                    onBack = { closeOverlay() }
                }
                view.render(world, coordinator.actorId)
                setContentView(view)
            }
            "SHOP" -> {
                val view = ShopScreen(this).apply {
                    onBack = { closeOverlay() }
                    onWorldAction = { action, target, amount -> runWorldActionOverlay("SHOP", action, target, amount) }
                }
                view.render(world, coordinator.actorId)
                setContentView(view)
            }
            "SHIP" -> {
                val view = ShipManagementScreen(this).apply {
                    onBack = { closeOverlay() }
                    onWorldAction = { action, target, amount -> runWorldActionOverlay("SHIP", action, target, amount) }
                }
                view.render(world)
                setContentView(view)
            }
            "CREW" -> {
                val view = CrewManagementScreen(this).apply {
                    onBack = { closeOverlay() }
                    onWorldAction = { action, target, amount -> runWorldActionOverlay("CREW", action, target, amount) }
                }
                view.render(world)
                setContentView(view)
            }
            "TRAINING" -> {
                val view = TrainingScreen(this).apply {
                    onBack = { closeOverlay() }
                    onWorldAction = { action, target, amount -> runWorldActionOverlay("TRAINING", action, target, amount) }
                }
                view.render(world, coordinator.actorId)
                setContentView(view)
            }
            else -> closeOverlay()
        }
    }

    private fun runWorldActionOverlay(overlay: String, action: String, target: String, amount: Int) {
        worker.execute {
            try {
                coordinator.submitWorldAction(action, target, amount)
                runOnUiThread { renderOverlay(overlay) }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this@MainActivity, t.message ?: "Ação recusada", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showInfoOverlay(title: String, body: String) {
        val view = InfoPanelScreen(this).apply {
            onBack = { closeOverlay() }
            render(title, body)
        }
        setContentView(view)
    }

    private fun closeOverlay() {
        currentOverlay = null
        gameplayView = null
        explorationView = null
        renderWorld()
    }

    private fun startSync() {
        stopSync()
        syncTask = worker.scheduleAtFixedRate({
            try {
                if (coordinator.mode == SessionMode.HOST_COOP) runCatching { coordinator.advertiseOnce() }
                coordinator.refresh()
                if (currentOverlay == null) postWorld()
            } catch (_: Throwable) {
                // Connection errors remain recoverable; next tick or explicit home reset can retry.
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun stopSync() {
        syncTask?.cancel(true)
        syncTask = null
    }

    private fun resetToHome() {
        stopSync()
        worker.execute {
            coordinator.close()
            coordinator = GameSessionCoordinator(saveRoot)
            runOnUiThread { showHome() }
        }
    }

    override fun onDestroy() {
        stopSync()
        coordinator.close()
        worker.shutdownNow()
        super.onDestroy()
    }
}
