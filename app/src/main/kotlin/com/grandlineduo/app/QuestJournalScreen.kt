package com.grandlineduo.app

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import grandlineduo.appshell.QuestJournalPresenter
import grandlineduo.core.model.WorldState
import grandlineduo.game.world.ExplorationQuestStatus

class QuestJournalScreen(context: Context) : ScrollView(context) {
    var onBack: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(24), dp(18), dp(30))
        setBackgroundColor(0xFF071218.toInt())
    }

    init {
        isFillViewport = true
        setBackgroundColor(0xFF071218.toInt())
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(world: WorldState, actorId: String) {
        root.removeAllViews()
        root.addView(label("DIÁRIO DE MISSÕES", 24f, 0xFFE5B758.toInt(), true))
        root.addView(label("${world.islandId.replace('-', ' ').replaceFirstChar { it.uppercase() }} • ${actorId.uppercase()}", 13f, 0xFFB6C8C2.toInt(), false).apply {
            setPadding(0, dp(4), 0, dp(10))
        })

        val entries = QuestJournalPresenter.entries(world, actorId)
        if (entries.isEmpty()) {
            root.addView(label("Nenhuma missão disponível nesta ilha.", 14f, 0xFFB6C8C2.toInt(), false))
        } else {
            entries.forEach { entry ->
                val active = entry.status == ExplorationQuestStatus.ACTIVE || entry.status == ExplorationQuestStatus.OBJECTIVE_COMPLETE
                val panel = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    setBackgroundColor(if (active) 0xFF17333A.toInt() else 0xFF10242B.toInt())
                }
                val type = if (entry.isBossHunt) "CAÇADA DE CHEFE" else "MISSÃO LOCAL"
                panel.addView(label(type, 11f, if (entry.isBossHunt) 0xFFF1B84B.toInt() else 0xFF6FD3C8.toInt(), true))
                panel.addView(label(entry.title, 17f, 0xFFF3ECD8.toInt(), true))
                panel.addView(label(statusLabel(entry.status), 12f, statusColor(entry.status), true).apply {
                    setPadding(0, dp(3), 0, dp(6))
                })
                panel.addView(label("Contratante: ${entry.giver}", 13f, 0xFFB6C8C2.toInt(), false))
                panel.addView(label("Objetivo: ${entry.objective}", 14f, 0xFFF3ECD8.toInt(), false).apply {
                    setPadding(0, dp(5), 0, dp(4))
                })
                panel.addView(label("Recompensa: ${entry.reward}", 13f, 0xFFE5B758.toInt(), false))
                root.addView(panel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(9)
                })
            }
        }

        root.addView(Button(context).apply {
            text = "VOLTAR AO MAPA"
            isAllCaps = false
            setOnClickListener { onBack?.invoke() }
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
    }

    private fun statusLabel(status: ExplorationQuestStatus): String = when (status) {
        ExplorationQuestStatus.AVAILABLE -> "DISPONÍVEL"
        ExplorationQuestStatus.ACTIVE -> "ATIVA"
        ExplorationQuestStatus.OBJECTIVE_COMPLETE -> "PRONTA PARA ENTREGA"
        ExplorationQuestStatus.TURNED_IN -> "CONCLUÍDA"
    }

    private fun statusColor(status: ExplorationQuestStatus): Int = when (status) {
        ExplorationQuestStatus.AVAILABLE -> 0xFFB6C8C2.toInt()
        ExplorationQuestStatus.ACTIVE -> 0xFFF1B84B.toInt()
        ExplorationQuestStatus.OBJECTIVE_COMPLETE -> 0xFF73D69A.toInt()
        ExplorationQuestStatus.TURNED_IN -> 0xFF7F9398.toInt()
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
