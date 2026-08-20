package com.grandlineduo.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import grandlineduo.appshell.ExplorationPresentation
import grandlineduo.appshell.GameAction
import grandlineduo.appshell.GamePresentation
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.game.world.ExplorationTile
import grandlineduo.game.world.ExplorationViewport
import grandlineduo.game.world.ExplorationViewportState

private object WorldUiColors {
    const val BACKGROUND = 0xFF08131CL
    const val PANEL = 0xFF102330L
    const val PANEL_ALT = 0xFF173342L
    const val TEXT = 0xFFF4E8C7L
    const val MUTED = 0xFFB4C5C9L
    const val ACCENT = 0xFFF1B84BL
    const val WATER = 0xFF174A68L
    const val WATER_DEEP = 0xFF0E324AL
    const val GRASS = 0xFF315D3CL
    const val SAND = 0xFFB89A62L
    const val STONE = 0xFF667078L
    const val ROAD = 0xFF8D7450L
    const val BUILDING = 0xFF563D3AL
    const val PLAYER = 0xFFFFD45AL
    const val OUTLINE = 0xFF071017L
    const val MARKER = 0xFFF7F0D8L
    const val NPC = 0xFF6FD3C8L
    const val QUEST = 0xFFFF8F4EL
}

class ExplorationScreen(context: Context) : ScrollView(context) {
    var onAction: ((GameAction) -> Unit)? = null
    var onHome: (() -> Unit)? = null

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(26))
        setBackgroundColor(WorldUiColors.BACKGROUND.toInt())
    }
    private val title = text(24f, bold = true)
    private val body = text(14f)
    private val status = text(13f)
    private val mapView = ExplorationMapView(context)
    private val locationHint = text(13f, bold = true)
    private val contextual = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val dpad = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        isFillViewport = true
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        root.addView(title)
        root.addView(body, marginTop(6))
        root.addView(status, marginTop(10))
        root.addView(mapView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(330)).apply {
            topMargin = dp(14)
        })
        root.addView(locationHint, marginTop(10))
        root.addView(contextual, marginTop(8))
        root.addView(dpad, marginTop(12))
        root.addView(actionButton("Voltar ao início") { onHome?.invoke() }, marginTop(18))
    }

    fun render(model: GamePresentation) {
        val exploration = requireNotNull(model.exploration) { "ExplorationScreen requires exploration presentation" }
        title.text = model.title
        body.text = model.body
        status.text = model.status.joinToString("\n")
        mapView.render(exploration)
        locationHint.text = interactionLabel(exploration.interaction)
        renderContextual(model.actions)
        renderDpad(model.actions)
    }

    private fun renderContextual(actions: List<GameAction>) {
        contextual.removeAllViews()
        val visible = actions.filter { it.kind != "EXPLORE_MOVE" }
        if (visible.isEmpty()) {
            contextual.addView(text(12f).apply { text = "Explore a ilha para encontrar pontos de interesse." })
            return
        }
        visible.forEach { action ->
            contextual.addView(
                actionButton(action.label) { onAction?.invoke(action) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) },
            )
        }
    }

    private fun renderDpad(actions: List<GameAction>) {
        dpad.removeAllViews()
        val north = actions.firstOrNull { it.kind == "EXPLORE_MOVE" && it.id == "NORTH" }
        val south = actions.firstOrNull { it.kind == "EXPLORE_MOVE" && it.id == "SOUTH" }
        val west = actions.firstOrNull { it.kind == "EXPLORE_MOVE" && it.id == "WEST" }
        val east = actions.firstOrNull { it.kind == "EXPLORE_MOVE" && it.id == "EAST" }

        dpad.addView(horizontalRow(null, north, null))
        dpad.addView(horizontalRow(west, null, east), marginTop(4))
        dpad.addView(horizontalRow(null, south, null), marginTop(4))
    }

    private fun horizontalRow(left: GameAction?, center: GameAction?, right: GameAction?): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(dpadCell(left))
            addView(dpadCell(center))
            addView(dpadCell(right))
        }

    private fun dpadCell(action: GameAction?): View {
        val params = LinearLayout.LayoutParams(dp(76), dp(58)).apply { marginStart = dp(3); marginEnd = dp(3) }
        if (action == null) return View(context).apply { layoutParams = params }
        return actionButton(
            when (action.id) {
                "NORTH" -> "▲"
                "SOUTH" -> "▼"
                "WEST" -> "◀"
                "EAST" -> "▶"
                else -> action.label
            },
        ) { onAction?.invoke(action) }.apply { layoutParams = params }
    }

    private fun interactionLabel(interaction: ExplorationInteraction?): String = when (interaction) {
        ExplorationInteraction.DOCK -> "⚓ Cais — escolha uma rota da Grand Line"
        ExplorationInteraction.MARKET -> "$ Mercado — comércio disponível aqui"
        ExplorationInteraction.TRAINING -> "✦ Área de treino — progressão disponível aqui"
        ExplorationInteraction.SHIP -> "▰ Navio — manutenção e melhorias disponíveis"
        ExplorationInteraction.CREW -> "● Tripulação — gestão dos companheiros"
        null -> "Caminhe pelas ruas. NPCs têm ! e objetivos ativos aparecem com ?."
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(WorldUiColors.TEXT.toInt())
        backgroundTintList = ColorStateList.valueOf(WorldUiColors.PANEL_ALT.toInt())
        setOnClickListener { click() }
    }

    private fun text(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(WorldUiColors.TEXT.toInt())
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun marginTop(value: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(value) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class ExplorationMapView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var viewport: ExplorationViewportState? = null
    private var presentation: ExplorationPresentation? = null

    fun render(presentation: ExplorationPresentation) {
        this.presentation = presentation
        viewport = ExplorationViewport.build(
            map = presentation.map,
            playerPosition = presentation.playerPosition,
            width = 11,
            height = 9,
        )
        val npcCount = presentation.map.npcs.size
        val activeObjectives = presentation.visibleQuestObjectives.size
        contentDescription = "Mapa da ilha. Posição ${presentation.playerPosition.x}, ${presentation.playerPosition.y}. $npcCount NPC. $activeObjectives objetivo ativo."
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = viewport ?: return
        val model = presentation ?: return
        canvas.drawColor(WorldUiColors.WATER_DEEP.toInt())

        val cellWidth = width.toFloat() / data.width
        val cellHeight = height.toFloat() / data.height
        val size = minOf(cellWidth, cellHeight)
        val mapWidth = size * data.width
        val mapHeight = size * data.height
        val startX = (width - mapWidth) / 2f
        val startY = (height - mapHeight) / 2f

        data.cells.forEach { cell ->
            val column = cell.position.x - data.origin.x
            val row = cell.position.y - data.origin.y
            val left = startX + column * size
            val top = startY + row * size
            val rect = RectF(left, top, left + size, top + size)

            fill.color = tileColor(cell.tile)
            canvas.drawRect(rect, fill)

            stroke.color = WorldUiColors.OUTLINE.toInt()
            stroke.alpha = 100
            canvas.drawRect(rect, stroke)
            stroke.alpha = 255

            if (cell.tile == ExplorationTile.WATER) {
                stroke.color = Color.argb(120, 180, 225, 245)
                val y = top + size * 0.55f
                canvas.drawLine(left + size * 0.15f, y, left + size * 0.42f, y - size * 0.08f, stroke)
                canvas.drawLine(left + size * 0.42f, y - size * 0.08f, left + size * 0.72f, y, stroke)
            }

            cell.interaction?.let { interaction ->
                fill.color = WorldUiColors.OUTLINE.toInt()
                canvas.drawCircle(rect.centerX(), rect.centerY(), size * 0.30f, fill)
                marker.color = WorldUiColors.MARKER.toInt()
                marker.textSize = size * 0.40f
                val baseline = rect.centerY() - (marker.ascent() + marker.descent()) / 2f
                canvas.drawText(interactionGlyph(interaction), rect.centerX(), baseline, marker)
            }

            if (cell.position in model.visibleQuestObjectives) {
                fill.color = WorldUiColors.QUEST.toInt()
                canvas.drawCircle(rect.right - size * 0.22f, rect.top + size * 0.22f, size * 0.18f, fill)
                marker.color = WorldUiColors.OUTLINE.toInt()
                marker.textSize = size * 0.28f
                val baseline = rect.top + size * 0.22f - (marker.ascent() + marker.descent()) / 2f
                canvas.drawText("?", rect.right - size * 0.22f, baseline, marker)
            }

            model.map.npcs[cell.position]?.let {
                fill.color = WorldUiColors.NPC.toInt()
                canvas.drawCircle(rect.left + size * 0.22f, rect.top + size * 0.22f, size * 0.18f, fill)
                marker.color = WorldUiColors.OUTLINE.toInt()
                marker.textSize = size * 0.28f
                val baseline = rect.top + size * 0.22f - (marker.ascent() + marker.descent()) / 2f
                canvas.drawText("!", rect.left + size * 0.22f, baseline, marker)
            }

            if (cell.isPlayer) {
                fill.color = WorldUiColors.PLAYER.toInt()
                canvas.drawCircle(rect.centerX(), rect.centerY(), size * 0.28f, fill)
                stroke.color = Color.WHITE
                stroke.strokeWidth = maxOf(resources.displayMetrics.density * 1.5f, 2f)
                canvas.drawCircle(rect.centerX(), rect.centerY(), size * 0.28f, stroke)
                stroke.strokeWidth = resources.displayMetrics.density
            }
        }
    }

    private fun tileColor(tile: ExplorationTile): Int = when (tile) {
        ExplorationTile.WATER -> WorldUiColors.WATER.toInt()
        ExplorationTile.GRASS -> WorldUiColors.GRASS.toInt()
        ExplorationTile.SAND -> WorldUiColors.SAND.toInt()
        ExplorationTile.STONE -> WorldUiColors.STONE.toInt()
        ExplorationTile.ROAD -> WorldUiColors.ROAD.toInt()
        ExplorationTile.BUILDING -> WorldUiColors.BUILDING.toInt()
    }

    private fun interactionGlyph(interaction: ExplorationInteraction): String = when (interaction) {
        ExplorationInteraction.DOCK -> "D"
        ExplorationInteraction.MARKET -> "M"
        ExplorationInteraction.TRAINING -> "T"
        ExplorationInteraction.SHIP -> "N"
        ExplorationInteraction.CREW -> "C"
    }
}
