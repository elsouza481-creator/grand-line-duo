package com.grandlineduo.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import grandlineduo.appshell.CharacterBuildPreset
import grandlineduo.appshell.CharacterPresetFactory
import grandlineduo.appshell.ClassPathDisplay
import grandlineduo.appshell.GameAction
import grandlineduo.appshell.GamePresentation
import grandlineduo.game.InventoryEngine
import grandlineduo.game.ItemCatalog
import grandlineduo.game.ItemType
import grandlineduo.game.EquipmentSlot
import grandlineduo.core.model.WorldState
import grandlineduo.game.character.Attribute
import grandlineduo.game.character.ClassMasteryEngine
import grandlineduo.game.character.ClassPath
import grandlineduo.game.character.Skill
import grandlineduo.game.powers.HakiType

private object Palette {
    const val BG = 0xFF071218.toInt()
    const val PANEL = 0xFF10242B.toInt()
    const val PANEL_2 = 0xFF17333A.toInt()
    const val GOLD = 0xFFE5B758.toInt()
    const val TEXT = 0xFFF3ECD8.toInt()
    const val MUTED = 0xFFB6C8C2.toInt()
    const val DANGER = 0xFFD76757.toInt()
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private fun TextView.baseText(sizeSp: Float = 16f) {
    setTextColor(Palette.TEXT)
    textSize = sizeSp
}

private fun LinearLayout.sectionTitle(text: String) {
    addView(TextView(context).apply {
        this.text = text
        baseText(13f)
        setTextColor(Palette.GOLD)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, context.dp(16), 0, context.dp(6))
    })
}

private fun LinearLayout.actionButton(label: String, onClick: () -> Unit): Button {
    val button = Button(context).apply {
        text = label
        setTextColor(Color.rgb(18, 28, 29))
        setBackgroundColor(Palette.GOLD)
        isAllCaps = false
        setOnClickListener { onClick() }
    }
    addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(52)).apply {
        topMargin = context.dp(8)
    })
    return button
}

class HomeScreen(context: Context) : ScrollView(context) {
    var onSolo: (() -> Unit)? = null
    var onHost: (() -> Unit)? = null
    var onJoin: (() -> Unit)? = null
    var onContinue: (() -> Unit)? = null

    private val status = TextView(context)
    private val continueButton: Button
    private val buttons = mutableListOf<Button>()

    init {
        setBackgroundColor(Palette.BG)
        isFillViewport = true
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(22), context.dp(42), context.dp(22), context.dp(28))
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        root.addView(TextView(context).apply {
            text = "GRAND LINE DUO"
            baseText(32f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.GOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(context).apply {
            text = "RPG hardcore • solo ou co-op local"
            baseText(14f)
            setTextColor(Palette.MUTED)
            gravity = Gravity.CENTER
            setPadding(0, context.dp(8), 0, context.dp(22))
        })
        root.addView(status.apply {
            baseText(14f)
            setTextColor(Palette.MUTED)
            gravity = Gravity.CENTER
            setPadding(context.dp(10), context.dp(12), context.dp(10), context.dp(12))
            setBackgroundColor(Palette.PANEL)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        buttons += root.actionButton("JOGAR SOLO") { onSolo?.invoke() }
        buttons += root.actionButton("HOSPEDAR CO-OP") { onHost?.invoke() }
        buttons += root.actionButton("ENTRAR NO CO-OP") { onJoin?.invoke() }
        continueButton = root.actionButton("CONTINUAR CAMPANHA") { onContinue?.invoke() }
        buttons += continueButton

        root.addView(TextView(context).apply {
            text = "Perfil visual leve: 30 FPS alvo, efeitos reduzidos e sem internet obrigatória durante a partida."
            baseText(12f)
            setTextColor(Palette.MUTED)
            gravity = Gravity.CENTER
            setPadding(0, context.dp(26), 0, 0)
        })
    }

    fun render(hasSave: Boolean, message: String = "Pronto para zarpar.", busy: Boolean = false) {
        status.text = message
        continueButton.visibility = if (hasSave) View.VISIBLE else View.GONE
        buttons.forEach { it.isEnabled = !busy }
    }
}

class CharacterCreationScreen(context: Context) : ScrollView(context) {
    var onCreateCharacter: ((grandlineduo.game.character.CharacterDraft) -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private val name = EditText(context)
    private val age = EditText(context)
    private val origin = spinner(listOf("East Blue", "West Blue", "North Blue", "South Blue", "Grand Line"))
    private val profession = spinner(listOf("Aventureiro", "Navegador", "Médico", "Engenheiro", "Cozinheiro", "Caçador", "Mercador"))
    private val combatStyle = spinner(listOf("Espadachim", "Lutador", "Atirador"))
    private val attributePreset = presetSpinner(CharacterPresetFactory.attributePresets())
    private val skillPreset = presetSpinner(CharacterPresetFactory.skillPresets())
    private val hair = spinner(listOf("Curto", "Longo", "Cacheado", "Raspado", "Tranças"))
    private val skin = spinner(listOf("Clara", "Média", "Morena", "Escura"))
    private val outfit = spinner(listOf("Marinheiro", "Casaco", "Aventureiro", "Médico", "Mecânico"))
    private val accent = spinner(listOf("Vermelho", "Azul", "Verde", "Amarelo", "Preto", "Branco"))
    private val error = TextView(context)

    init {
        setBackgroundColor(Palette.BG)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(26), context.dp(20), context.dp(32))
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        root.addView(TextView(context).apply {
            text = "CRIAR PERSONAGEM"
            baseText(26f)
            setTextColor(Palette.GOLD)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(context).apply {
            text = "A ficha segue as regras hardcore. Escolha o visual e uma distribuição válida de atributos/perícias."
            baseText(13f)
            setTextColor(Palette.MUTED)
            setPadding(0, context.dp(6), 0, context.dp(10))
        })

        root.sectionTitle("IDENTIDADE")
        name.hint = "Nome"
        name.setTextColor(Palette.TEXT)
        name.setHintTextColor(Palette.MUTED)
        name.setBackgroundColor(Palette.PANEL_2)
        name.setPadding(context.dp(12), 0, context.dp(12), 0)
        root.addView(name, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(50)))
        age.hint = "Idade"
        age.inputType = InputType.TYPE_CLASS_NUMBER
        age.setText("21")
        age.setTextColor(Palette.TEXT)
        age.setHintTextColor(Palette.MUTED)
        age.setBackgroundColor(Palette.PANEL_2)
        age.setPadding(context.dp(12), 0, context.dp(12), 0)
        root.addView(age, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(50)).apply { topMargin = context.dp(8) })
        addLabeled(root, "Origem", origin)
        addLabeled(root, "Profissão", profession)
        addLabeled(root, "Estilo de combate", combatStyle)

        root.sectionTitle("VISUAL")
        addLabeled(root, "Cabelo", hair)
        addLabeled(root, "Tom de pele", skin)
        addLabeled(root, "Roupa", outfit)
        addLabeled(root, "Cor de destaque", accent)

        root.sectionTitle("TREINAMENTO INICIAL")
        addLabeled(root, "Atributos", attributePreset)
        addLabeled(root, "Perícias", skillPreset)

        root.addView(error.apply {
            baseText(13f)
            setTextColor(Palette.DANGER)
            visibility = View.GONE
            setPadding(0, context.dp(12), 0, 0)
        })
        root.actionButton("CONFIRMAR PERSONAGEM") { submit() }
        root.actionButton("VOLTAR") { onBack?.invoke() }
    }

    private fun submit() {
        val cleanName = name.text.toString().trim()
        val parsedAge = age.text.toString().toIntOrNull()
        if (cleanName.length < 2 || parsedAge == null || parsedAge !in 10..90) {
            error.text = "Informe um nome com pelo menos 2 letras e idade entre 10 e 90."
            error.visibility = View.VISIBLE
            return
        }
        error.visibility = View.GONE
        val attrs = selectedPreset(attributePreset, CharacterPresetFactory.attributePresets())
        val skills = selectedPreset(skillPreset, CharacterPresetFactory.skillPresets())
        onCreateCharacter?.invoke(
            CharacterPresetFactory.createDraft(
                name = cleanName,
                age = parsedAge,
                origin = origin.selectedItem.toString(),
                profession = profession.selectedItem.toString(),
                combatStyle = combatStyle.selectedItem.toString(),
                attributePreset = attrs.id,
                skillPreset = skills.id,
                hair = hair.selectedItem.toString(),
                skin = skin.selectedItem.toString(),
                outfit = outfit.selectedItem.toString(),
                accent = accent.selectedItem.toString(),
            )
        )
    }

    private fun spinner(values: List<String>): Spinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
        setBackgroundColor(Palette.PANEL_2)
    }

    private fun presetSpinner(values: List<CharacterBuildPreset>): Spinner = spinner(values.map { it.label })

    private fun selectedPreset(spinner: Spinner, values: List<CharacterBuildPreset>): CharacterBuildPreset =
        values[spinner.selectedItemPosition.coerceIn(values.indices)]

    private fun addLabeled(root: LinearLayout, label: String, spinner: Spinner) {
        root.addView(TextView(context).apply {
            text = label
            baseText(12f)
            setTextColor(Palette.MUTED)
            setPadding(0, context.dp(10), 0, context.dp(4))
        })
        root.addView(spinner, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(48)))
    }
}

class GameplayScreen(context: Context) : ScrollView(context) {
    var onAction: ((GameAction) -> Unit)? = null
    var onHome: (() -> Unit)? = null
    private val root = LinearLayout(context)

    init {
        setBackgroundColor(Palette.BG)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(context.dp(18), context.dp(24), context.dp(18), context.dp(30))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(model: GamePresentation) {
        root.removeAllViews()
        root.addView(TextView(context).apply {
            text = model.title
            baseText(24f)
            setTextColor(Palette.GOLD)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(context).apply {
            text = model.body
            baseText(16f)
            setPadding(0, context.dp(14), 0, context.dp(14))
            setLineSpacing(0f, 1.12f)
        })
        if (model.status.isNotEmpty()) {
            root.addView(TextView(context).apply {
                text = model.status.joinToString("\n")
                baseText(13f)
                setTextColor(Palette.MUTED)
                setBackgroundColor(Palette.PANEL)
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        if (model.actions.isNotEmpty()) {
            root.sectionTitle("AÇÕES")
            model.actions.forEach { action -> root.actionButton(action.label) { onAction?.invoke(action) } }
        } else {
            root.addView(TextView(context).apply {
                text = "Aguardando o próximo evento…"
                baseText(13f)
                setTextColor(Palette.MUTED)
                gravity = Gravity.CENTER
                setPadding(0, context.dp(24), 0, context.dp(12))
            })
        }
        root.actionButton("MENU PRINCIPAL") { onHome?.invoke() }
    }
}

class InventoryScreen(context: Context) : ScrollView(context) {
    var onInventoryAction: ((String, String) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    private val root = LinearLayout(context)

    init {
        setBackgroundColor(Palette.BG)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(context.dp(18), context.dp(24), context.dp(18), context.dp(30))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(world: WorldState, actorId: String) {
        root.removeAllViews()
        root.addView(TextView(context).apply {
            text = "INVENTÁRIO"
            baseText(24f)
            setTextColor(Palette.GOLD)
            typeface = Typeface.DEFAULT_BOLD
        })
        val state = InventoryEngine.read(world, actorId)
        if (state.items.isEmpty()) {
            root.addView(TextView(context).apply { text = "Inventário vazio."; baseText(14f) })
        } else {
            state.items.toSortedMap().forEach { (itemId, amount) ->
                val definition = runCatching { ItemCatalog.get(itemId) }.getOrNull()
                val equipped = state.equipped.values.contains(itemId)
                val panel = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
                    setBackgroundColor(Palette.PANEL)
                }
                panel.addView(TextView(context).apply {
                    text = "${definition?.name ?: itemId}  ×$amount${if (equipped) "  • EQUIPADO" else ""}"
                    baseText(15f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                val action = when {
                    definition == null -> null
                    definition.type == ItemType.CONSUMABLE -> "USE"
                    definition.type in setOf(ItemType.WEAPON, ItemType.ARMOR, ItemType.CHARM) && !equipped -> "EQUIP"
                    definition.type in setOf(ItemType.WEAPON, ItemType.ARMOR, ItemType.CHARM) && equipped -> "UNEQUIP"
                    else -> null
                }
                if (action != null) {
                    panel.addView(Button(context).apply {
                        text = when (action) { "USE" -> "Usar"; "EQUIP" -> "Equipar"; else -> "Desequipar" }
                        isAllCaps = false
                        setOnClickListener {
                            val target = if (action == "UNEQUIP") when (definition!!.type) {
                                ItemType.WEAPON -> EquipmentSlot.WEAPON.name
                                ItemType.ARMOR -> EquipmentSlot.ARMOR.name
                                ItemType.CHARM -> EquipmentSlot.CHARM.name
                                else -> itemId
                            } else itemId
                            onInventoryAction?.invoke(action, target)
                        }
                    })
                }
                root.addView(panel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
            }
        }
        root.actionButton("VOLTAR") { onBack?.invoke() }
    }
}

class InfoPanelScreen(context: Context) : ScrollView(context) {
    var onBack: (() -> Unit)? = null
    private val root = LinearLayout(context)

    init {
        setBackgroundColor(Palette.BG)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(context.dp(18), context.dp(24), context.dp(18), context.dp(30))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(title: String, body: String) {
        root.removeAllViews()
        root.addView(TextView(context).apply { text = title; baseText(24f); setTextColor(Palette.GOLD); typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(context).apply { text = body; baseText(15f); setPadding(0, context.dp(12), 0, context.dp(12)) })
        root.actionButton("VOLTAR") { onBack?.invoke() }
    }
}

class ShopScreen(context: Context) : ScrollView(context) {
    var onWorldAction: ((String, String, Int) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    private val root = LinearLayout(context)

    init {
        setBackgroundColor(Palette.BG)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(context.dp(18), context.dp(24), context.dp(18), context.dp(30))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(world: WorldState, actorId: String) {
        root.removeAllViews()
        root.addView(TextView(context).apply {
            text = "MERCADO • ${world.islandId.replace('-', ' ').uppercase()}"
            baseText(22f)
            setTextColor(Palette.GOLD)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(context).apply {
            text = "Caixa compartilhado: ${world.partyBerries} Berries"
            baseText(13f)
            setTextColor(Palette.MUTED)
            setPadding(0, context.dp(8), 0, context.dp(12))
        })
        root.sectionTitle("COMPRAR")
        grandlineduo.game.ShopEngine.stockFor(world.islandId).forEach { item ->
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
                setBackgroundColor(Palette.PANEL)
            }
            panel.addView(TextView(context).apply {
                text = "${item.name} • ${item.valueBerries} Berries"
                baseText(14f)
                typeface = Typeface.DEFAULT_BOLD
            })
            panel.addView(TextView(context).apply {
                text = item.description
                baseText(12f)
                setTextColor(Palette.MUTED)
            })
            panel.addView(Button(context).apply {
                text = "Comprar 1"
                isAllCaps = false
                setOnClickListener { onWorldAction?.invoke("SHOP_BUY", item.id, 1) }
            })
            root.addView(panel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(7) })
        }

        val inventory = InventoryEngine.read(world, actorId)
        val sellable = inventory.items.keys.mapNotNull { id ->
            runCatching { ItemCatalog.get(id) }.getOrNull()?.takeIf {
                it.valueBerries > 0 && it.type != ItemType.KEY && id !in inventory.equipped.values
            }
        }
        if (sellable.isNotEmpty()) {
            root.sectionTitle("VENDER")
            sellable.forEach { item ->
                root.actionButton("Vender ${item.name} • ${item.valueBerries / 2} B") {
                    onWorldAction?.invoke("SHOP_SELL", item.id, 1)
                }
            }
        }
        root.actionButton("VOLTAR") { onBack?.invoke() }
    }
}

class ShipManagementScreen(context: Context) : ScrollView(context) {
    var onWorldAction: ((String, String, Int) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    private val root = LinearLayout(context)

    init {
        setBackgroundColor(Palette.BG)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(context.dp(18), context.dp(24), context.dp(18), context.dp(30))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(world: WorldState) {
        root.removeAllViews()
        val ship = world.shipState
        root.addView(TextView(context).apply {
            text = ship?.name?.uppercase() ?: "NAVIO"
            baseText(24f)
            setTextColor(Palette.GOLD)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (ship == null) {
            root.addView(TextView(context).apply { text = "Nenhum navio disponível."; baseText(14f) })
        } else {
            root.addView(TextView(context).apply {
                text = "Casco ${ship.hull}/${ship.maxHull}\nSuprimentos ${ship.supplies}/${ship.maxSupplies}\nVelocidade ${ship.speed} • Manobra ${ship.maneuverability} • Artilharia ${ship.artillery}\nCaixa ${world.partyBerries} Berries"
                baseText(14f)
                setBackgroundColor(Palette.PANEL)
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            })
            root.sectionTitle("MANUTENÇÃO")
            if (ship.hull < ship.maxHull) root.actionButton("Reparar até 5 de casco") { onWorldAction?.invoke("SHIP_REPAIR", "", 5) }
            if (ship.supplies < ship.maxSupplies) root.actionButton("Reabastecer até 5 unidades") { onWorldAction?.invoke("SHIP_RESUPPLY", "", 5) }
            root.sectionTitle("MELHORIAS")
            grandlineduo.game.ship.ShipUpgrade.entries.forEach { upgrade ->
                val level = ship.upgrades[upgrade] ?: 0
                if (level < 5) {
                    root.actionButton("${upgrade.name.replace('_', ' ')} • nível $level → ${level + 1}") {
                        onWorldAction?.invoke("SHIP_UPGRADE", upgrade.name, 1)
                    }
                }
            }
        }
        root.actionButton("VOLTAR") { onBack?.invoke() }
    }
}

class CrewManagementScreen(context: Context) : ScrollView(context) {
    var onWorldAction: ((String, String, Int) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    private val root = LinearLayout(context)

    init {
        setBackgroundColor(Palette.BG)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(context.dp(18), context.dp(24), context.dp(18), context.dp(30))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(world: WorldState) {
        root.removeAllViews()
        root.addView(TextView(context).apply {
            text = "TRIPULAÇÃO"
            baseText(24f)
            setTextColor(Palette.GOLD)
            typeface = Typeface.DEFAULT_BOLD
        })
        val members = world.crewState.members.values.sortedBy { it.name }
        if (members.isEmpty()) {
            root.addView(TextView(context).apply { text = "O navio ainda não possui especialistas NPC."; baseText(14f) })
        } else {
            root.sectionTitle("A BORDO")
            members.forEach { member ->
                val panel = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
                    setBackgroundColor(Palette.PANEL)
                }
                panel.addView(TextView(context).apply {
                    text = "${member.name} • ${member.role.name.replace('_', ' ')}\nCompetência ${member.competence} • Lealdade ${member.loyalty} • ${member.status.name}"
                    baseText(13f)
                })
                val roles = grandlineduo.game.crew.CrewRole.entries
                val roleSpinner = Spinner(context).apply {
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, roles.map { it.name.replace('_', ' ') })
                    setBackgroundColor(Palette.PANEL_2)
                }
                panel.addView(roleSpinner, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(46)))
                panel.addView(Button(context).apply {
                    text = "Alterar função"
                    isAllCaps = false
                    setOnClickListener {
                        val role = roles[roleSpinner.selectedItemPosition.coerceIn(roles.indices)]
                        onWorldAction?.invoke("CREW_ROLE", "${member.npcId}|${role.name}", 1)
                    }
                })
                root.addView(panel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
            }
        }

        val candidates = grandlineduo.game.crew.CrewRecruitmentCatalog.candidates(world.islandId)
            .filter { it.npcId !in world.crewState.members }
        if (candidates.isNotEmpty()) {
            root.sectionTitle("RECRUTÁVEIS NESTA ILHA")
            candidates.forEach { candidate ->
                root.actionButton("Recrutar ${candidate.name} • ${candidate.role.name.replace('_', ' ')}") {
                    onWorldAction?.invoke("CREW_RECRUIT", candidate.npcId, 1)
                }
            }
        }
        root.actionButton("VOLTAR") { onBack?.invoke() }
    }
}


class TrainingScreen(context: Context) : ScrollView(context) {
    var onWorldAction: ((String, String, Int) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    private val root = LinearLayout(context)

    init {
        setBackgroundColor(Palette.BG)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(context.dp(18), context.dp(24), context.dp(18), context.dp(30))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(world: WorldState, actorId: String) {
        root.removeAllViews()
        val player = world.players[actorId]
        val profile = player?.profile
        root.addView(TextView(context).apply {
            text = "PODERES E TREINO"
            baseText(24f)
            setTextColor(Palette.GOLD)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (profile == null) {
            root.addView(TextView(context).apply { text = "Crie o personagem antes de treinar."; baseText(14f) })
            root.actionButton("VOLTAR") { onBack?.invoke() }
            return
        }
        root.addView(TextView(context).apply {
            text = "PEV ${profile.evolutionPoints} • PE ${player.energy}/${player.maxEnergy}"
            baseText(14f)
            setTextColor(Palette.MUTED)
            setPadding(0, context.dp(8), 0, context.dp(8))
        })

        root.sectionTitle("CLASSE E MAESTRIA")
        val classPaths = ClassPath.entries
        val classMastery = profile.classMastery
        if (classMastery == null) {
            root.addView(TextView(context).apply {
                text = "Este personagem veio de um save antigo e ainda não possui classe primária. A escolha abaixo é permanente."
                baseText(13f)
                setTextColor(Palette.MUTED)
                setBackgroundColor(Palette.PANEL)
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            })
            val classSpinner = Spinner(context).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, classPaths.map(ClassPathDisplay::label))
                setBackgroundColor(Palette.PANEL_2)
            }
            root.addView(classSpinner, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(48)).apply { topMargin = context.dp(8) })
            root.actionButton("Escolher classe primária") {
                val path = classPaths[classSpinner.selectedItemPosition.coerceIn(classPaths.indices)]
                onWorldAction?.invoke("CHOOSE_CLASS", path.name, 1)
            }
        } else {
            val primary = classMastery.primaryClass
            val perks = ClassMasteryEngine.unlockedPerks(primary, classMastery.levelOf(primary))
            root.addView(TextView(context).apply {
                text = buildString {
                    append(ClassPathDisplay.primaryProgress(classMastery))
                    if (perks.isNotEmpty()) append("\nMarcos: ").append(perks.joinToString(" • "))
                    append("\nA classe primária não muda. Outras classes podem ser treinadas como maestrias secundárias.")
                }
                baseText(13f)
                setTextColor(Palette.MUTED)
                setBackgroundColor(Palette.PANEL)
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            })
            val classSpinner = Spinner(context).apply {
                adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    classPaths.map { path ->
                        val primaryMark = if (path == primary) " • PRIMÁRIA" else ""
                        "${ClassPathDisplay.progress(classMastery, path)}$primaryMark"
                    },
                )
                setBackgroundColor(Palette.PANEL_2)
            }
            classSpinner.setSelection(classPaths.indexOf(primary).coerceAtLeast(0))
            root.addView(classSpinner, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(48)).apply { topMargin = context.dp(8) })
            root.actionButton("Treinar classe selecionada • +25 XP") {
                val path = classPaths[classSpinner.selectedItemPosition.coerceIn(classPaths.indices)]
                onWorldAction?.invoke("TRAIN_CLASS", path.name, 1)
            }
        }

        root.sectionTitle("ATRIBUTOS")
        val attributes = Attribute.entries
        val attributeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, attributes.map { "${it.name} • ${profile.attributes.getValue(it)}" })
            setBackgroundColor(Palette.PANEL_2)
        }
        root.addView(attributeSpinner, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(48)))
        root.actionButton("Treinar atributo selecionado") {
            onWorldAction?.invoke("TRAIN_ATTRIBUTE", attributes[attributeSpinner.selectedItemPosition.coerceIn(attributes.indices)].name, 1)
        }
        root.actionButton("Evoluir atributo selecionado • 3 PEV") {
            onWorldAction?.invoke("UPGRADE_ATTRIBUTE", attributes[attributeSpinner.selectedItemPosition.coerceIn(attributes.indices)].name, 1)
        }

        root.sectionTitle("PERÍCIAS")
        val skills = Skill.entries
        val skillSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, skills.map { "${it.name.replace('_', ' ')} • ${profile.skills[it] ?: 0}" })
            setBackgroundColor(Palette.PANEL_2)
        }
        root.addView(skillSpinner, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(48)))
        root.actionButton("Treinar perícia selecionada") {
            onWorldAction?.invoke("TRAIN_SKILL", skills[skillSpinner.selectedItemPosition.coerceIn(skills.indices)].name, 1)
        }
        root.actionButton("Evoluir perícia selecionada • 2 PEV") {
            onWorldAction?.invoke("UPGRADE_SKILL", skills[skillSpinner.selectedItemPosition.coerceIn(skills.indices)].name, 1)
        }

        root.sectionTitle("HAKI")
        listOf(HakiType.KENBUNSHOKU, HakiType.BUSOSHOKU).forEach { type ->
            val discipline = profile.haki.disciplines[type]
            if (discipline == null) {
                root.actionButton("Tentar despertar ${type.name.lowercase().replaceFirstChar { it.uppercase() }}") {
                    onWorldAction?.invoke("HAKI_AWAKEN", type.name, 1)
                }
            } else {
                root.addView(TextView(context).apply {
                    text = "${type.name} • domínio ${discipline.mastery}/6 • usos ${discipline.useCount}"
                    baseText(13f)
                })
                if (discipline.mastery < 6) root.actionButton("Treinar domínio ${type.name}") {
                    onWorldAction?.invoke("HAKI_TRAIN", type.name, 1)
                }
            }
        }
        val chapter = world.worldFlags["campaign.chapter"]?.toIntOrNull() ?: 0
        val haoshoku = profile.haki.disciplines[HakiType.HAOSHOKU]
        if (haoshoku != null) {
            root.addView(TextView(context).apply { text = "HAOSHOKU • domínio ${haoshoku.mastery}/6 • usos ${haoshoku.useCount}"; baseText(13f) })
            if (haoshoku.mastery < 6) root.actionButton("Treinar domínio HAOSHOKU") { onWorldAction?.invoke("HAKI_TRAIN", HakiType.HAOSHOKU.name, 1) }
        } else if (profile.haki.latentHaoshoku && chapter >= 4) {
            root.actionButton("Forçar a vontade ao limite") { onWorldAction?.invoke("HAKI_AWAKEN", HakiType.HAOSHOKU.name, 1) }
        }

        root.sectionTitle("AKUMA NO MI")
        val fruit = profile.devilFruit
        val discoveredFruit = world.worldFlags["fruit.discovery.id"]
        when {
            fruit != null -> {
                root.addView(TextView(context).apply {
                    text = "${fruit.revealedName ?: "Poder de fruta ainda não identificado"} • ${fruit.category.name} • domínio ${fruit.mastery}/6 • usos ${fruit.useCount}"
                    baseText(13f)
                })
                if (fruit.revealedName == null && world.partyBerries >= 1_000L) {
                    root.actionButton("Identificar fruta • 1.000 Berries") { onWorldAction?.invoke("FRUIT_IDENTIFY", fruit.fruitId, 1) }
                }
                if (fruit.mastery < 6) root.actionButton("Treinar domínio da Akuma no Mi") { onWorldAction?.invoke("FRUIT_TRAIN", fruit.fruitId, 1) }
            }
            discoveredFruit != null -> {
                root.addView(TextView(context).apply {
                    text = "Uma Fruta do Diabo desconhecida foi encontrada. Comer é permanente: o personagem perderá a capacidade de nadar."
                    baseText(13f)
                    setTextColor(Palette.MUTED)
                })
                root.actionButton("COMER A FRUTA DESCONHECIDA") { onWorldAction?.invoke("FRUIT_EAT", discoveredFruit, 1) }
            }
            else -> root.addView(TextView(context).apply { text = "Nenhuma Akuma no Mi foi encontrada nesta campanha até agora."; baseText(13f); setTextColor(Palette.MUTED) })
        }

        root.actionButton("VOLTAR") { onBack?.invoke() }
    }
}
