package grandlineduo.game.arc

import java.util.Random

object ArcEngine {
    private val HUMAN_PLAYER_IDS = setOf("p1", "p2", "p3", "p4")

    fun start(context: ArcStartContext): ArcState {
        require(context.islandId.isNotBlank()) { "Island id is required" }
        val participants = context.participantIds.toSortedSet()
        require(participants.size in 2..4) { "Narrative arc requires two to four participants" }
        require("p1" in participants) { "Narrative arc requires host player p1" }
        require(participants.all { it in HUMAN_PLAYER_IDS }) { "Invalid narrative participant" }
        val archetype = chooseArchetype(context)
        return ArcState(
            arcId = "${context.islandId}:${archetype.name.lowercase()}:${context.seed}",
            islandId = context.islandId,
            seed = context.seed,
            archetype = archetype,
            privateClues = participants.associateWith { emptySet<String>() },
        )
    }

    fun view(state: ArcState, playerId: String): ArcView {
        requirePlayer(state, playerId)
        val choices = when (state.phase) {
            ArcPhase.ARRIVAL -> if (playerId == "p1") listOf(
                ArcChoice("help_locals", "Ajudar moradores e ouvir o que aconteceu"),
                ArcChoice("approach_openly", "Entrar abertamente e pressionar a autoridade local"),
            ) else listOf(
                ArcChoice("shadow_authority", "Seguir discretamente agentes ligados ao conflito"),
                ArcChoice("survey_route", "Mapear acessos e rotas de fuga"),
            )
            ArcPhase.INVESTIGATION -> if (playerId == "p1") listOf(
                ArcChoice("question_contacts", "Cruzar depoimentos e contatos"),
                ArcChoice("force_information", "Forçar uma fonte hostil a falar"),
            ) else buildList {
                if (state.privateClues[playerId].orEmpty().isNotEmpty()) {
                    add(ArcChoice("reveal_intel", "Revelar a informação secreta à tripulação"))
                    add(ArcChoice("keep_intel", "Guardar a informação por enquanto"))
                }
                add(ArcChoice("scout_target", "Observar o alvo antes da escalada"))
            }
            ArcPhase.ESCALATION -> if (playerId == "p1") listOf(
                ArcChoice("challenge_enforcers", "Enfrentar os executores do conflito"),
                ArcChoice("secure_escape", "Preparar uma rota segura para civis e aliados"),
            ) else listOf(
                ArcChoice("sabotage_support", "Sabotar reforços e comunicações"),
                ArcChoice("protect_civilians", "Proteger civis enquanto a tripulação avança"),
            )
            ArcPhase.CLIMAX -> if (playerId == "p1") listOf(
                ArcChoice("direct_assault", "Forçar o confronto decisivo"),
                ArcChoice("draw_boss", "Atrair o líder para terreno desfavorável"),
            ) else listOf(
                ArcChoice("exploit_weakness", "Explorar a fraqueza descoberta"),
                ArcChoice("support_partner", "Criar uma abertura para o ataque da tripulação"),
            )
            ArcPhase.AFTERMATH -> if (playerId == "p1") listOf(
                ArcChoice("spare_enemy", "Poupar o derrotado e exigir respostas"),
                ArcChoice("claim_reward", "Recolher recursos e partir antes dos reforços"),
            ) else listOf(
                ArcChoice("share_evidence", "Tornar públicas as provas encontradas"),
                ArcChoice("erase_traces", "Apagar rastros da participação da tripulação"),
            )
            ArcPhase.COMPLETE -> emptyList()
        }
        return ArcView(
            phase = state.phase,
            title = titleFor(state),
            description = descriptionFor(state, playerId),
            choices = choices,
        )
    }

    fun choose(state: ArcState, playerId: String, choiceId: String): ArcOutcome {
        requirePlayer(state, playerId)
        if (state.phase == ArcPhase.COMPLETE) throw ArcChoiceException("Arc is complete")
        if (playerId in state.actedThisPhase) throw ArcChoiceException("$playerId already acted in ${state.phase}")
        val allowed = view(state, playerId).choices.map { it.id }.toSet()
        if (choiceId !in allowed) throw ArcChoiceException("Choice $choiceId is not available to $playerId")

        var shared = state.sharedFlags
        var privateClues = state.privateClues
        var escalation = state.escalation
        val beats = mutableListOf<ArcBeat>()

        when (choiceId) {
            "help_locals" -> {
                shared = shared + "LOCALS_HELPED"
                beats += sharedBeat(state, "A população percebe que a tripulação não chegou apenas para saquear e começa a falar.")
            }
            "approach_openly" -> {
                shared = shared + "AUTHORITY_CHALLENGED"
                escalation += 1
                beats += sharedBeat(state, "A chegada aberta coloca a autoridade local em alerta e acelera o conflito.")
            }
            "shadow_authority" -> {
                val clue = clueFor(state.archetype)
                privateClues = addPrivate(privateClues, playerId, clue)
                beats += privateBeat(playerId, privateClueText(state.archetype))
            }
            "survey_route" -> {
                privateClues = addPrivate(privateClues, playerId, "SAFE_ROUTE")
                beats += privateBeat(playerId, "Você identifica uma rota lateral que evita os principais postos de vigilância.")
            }
            "question_contacts" -> {
                shared = shared + "CONTACTS_QUESTIONED"
                beats += sharedBeat(state, "Os relatos independentes apontam para o mesmo centro de poder na ilha.")
            }
            "force_information" -> {
                shared = shared + "SOURCE_PRESSURED"
                escalation += 1
                beats += sharedBeat(state, "A fonte cede, mas a pressão deixa claro para os inimigos que alguém está investigando.")
            }
            "reveal_intel" -> {
                val clue = privateClues[playerId].orEmpty().sorted().first()
                shared = shared + "INTEL_REVEALED:$clue"
                beats += sharedBeat(state, "$playerId revela à tripulação a informação secreta obtida durante a investigação.")
            }
            "keep_intel" -> beats += privateBeat(playerId, "Você decide manter a informação em segredo por enquanto.")
            "scout_target" -> {
                privateClues = addPrivate(privateClues, playerId, "TARGET_PATTERN")
                beats += privateBeat(playerId, "Você observa o padrão do alvo e descobre uma janela curta para agir.")
            }
            "challenge_enforcers" -> {
                shared = shared + "ENFORCERS_DEFEATED"
                escalation += 1
                beats += sharedBeat(state, "Os executores são enfrentados de frente; agora o líder do conflito sabe quem vocês são.")
            }
            "secure_escape" -> shared = shared + "ESCAPE_SECURED"
            "sabotage_support" -> {
                shared = shared + "SUPPORT_SABOTAGED"
                escalation += 1
            }
            "protect_civilians" -> shared = shared + "CIVILIANS_PROTECTED"
            "direct_assault" -> {
                shared = shared + "CLIMAX_DIRECT"
                escalation += 1
            }
            "draw_boss" -> shared = shared + "CLIMAX_TACTICAL"
            "exploit_weakness" -> shared = shared + "WEAKNESS_EXPLOITED"
            "support_partner" -> shared = shared + "PARTNER_SUPPORTED"
            "spare_enemy" -> shared = shared + "ENEMY_SPARED"
            "claim_reward" -> shared = shared + "REWARD_CLAIMED"
            "share_evidence" -> shared = shared + "EVIDENCE_PUBLIC"
            "erase_traces" -> shared = shared + "TRACES_ERASED"
        }

        var next = state.copy(
            sharedFlags = shared,
            privateClues = privateClues,
            actedThisPhase = state.actedThisPhase + playerId,
            escalation = escalation.coerceIn(0, 10),
        )
        next = advanceIfReady(next)
        return ArcOutcome(next, beats)
    }

    private fun chooseArchetype(context: ArcStartContext): ArcArchetype {
        val candidates = buildList {
            if ("MARINES" in context.presentFactions) add(ArcArchetype.MARINE_OCCUPATION)
            if ("UNDERWORLD" in context.presentFactions) add(ArcArchetype.UNDERWORLD_SMUGGLING)
            if ("PIRATES" in context.presentFactions) add(ArcArchetype.PIRATE_TYRANNY)
            if ("ANCIENT_RUINS" in context.worldFlags) add(ArcArchetype.RUINS_MYSTERY)
        }.ifEmpty { listOf(ArcArchetype.ISLAND_CRISIS) }
        val random = Random(context.seed xor context.islandId.hashCode().toLong() xor context.totalBounty)
        return candidates[random.nextInt(candidates.size)]
    }

    private fun advanceIfReady(state: ArcState): ArcState {
        if (state.actedThisPhase != state.participantIds) return state
        val next = when (state.phase) {
            ArcPhase.ARRIVAL -> ArcPhase.INVESTIGATION
            ArcPhase.INVESTIGATION -> ArcPhase.ESCALATION
            ArcPhase.ESCALATION -> ArcPhase.CLIMAX
            ArcPhase.CLIMAX -> ArcPhase.AFTERMATH
            ArcPhase.AFTERMATH -> ArcPhase.COMPLETE
            ArcPhase.COMPLETE -> ArcPhase.COMPLETE
        }
        return state.copy(phase = next, actedThisPhase = emptySet())
    }

    private fun clueFor(archetype: ArcArchetype): String = when (archetype) {
        ArcArchetype.MARINE_OCCUPATION -> "SEALED_MARINE_ORDERS"
        ArcArchetype.UNDERWORLD_SMUGGLING -> "SMUGGLER_MANIFEST"
        ArcArchetype.PIRATE_TYRANNY -> "CAPTAIN_TRIBUTE_LEDGER"
        ArcArchetype.RUINS_MYSTERY -> "RUIN_INSCRIPTION"
        ArcArchetype.ISLAND_CRISIS -> "HIDDEN_CAUSE"
    }

    private fun privateClueText(archetype: ArcArchetype): String = when (archetype) {
        ArcArchetype.MARINE_OCCUPATION -> "Você intercepta ordens seladas da Marinha que revelam o verdadeiro objetivo da operação na ilha."
        ArcArchetype.UNDERWORLD_SMUGGLING -> "Você encontra um manifesto de contrabando com nomes, horários e uma carga que não deveria existir."
        ArcArchetype.PIRATE_TYRANNY -> "Você encontra um livro de tributos que prova como o capitão mantém a ilha sob controle."
        ArcArchetype.RUINS_MYSTERY -> "Uma inscrição escondida revela que as ruínas não são o que os habitantes acreditam."
        ArcArchetype.ISLAND_CRISIS -> "Você encontra evidências privadas sobre a causa real da crise que atinge a ilha."
    }

    private fun titleFor(state: ArcState): String = when (state.archetype) {
        ArcArchetype.MARINE_OCCUPATION -> "A Bandeira Sobre ${state.islandId}"
        ArcArchetype.UNDERWORLD_SMUGGLING -> "Carga Sem Registro"
        ArcArchetype.PIRATE_TYRANNY -> "Tributo ao Capitão"
        ArcArchetype.RUINS_MYSTERY -> "Ecos Sob a Ilha"
        ArcArchetype.ISLAND_CRISIS -> "A Ilha em Ruptura"
    }

    private fun descriptionFor(state: ArcState, playerId: String): String =
        "${state.phase.name.lowercase().replaceFirstChar { it.uppercase() }} em ${state.islandId}. $playerId decide como agir enquanto o GM acompanha as consequências."

    private fun addPrivate(current: Map<String, Set<String>>, playerId: String, clue: String): Map<String, Set<String>> =
        current + (playerId to (current[playerId].orEmpty() + clue))

    private fun sharedBeat(state: ArcState, text: String) = ArcBeat(text, state.participantIds)
    private fun privateBeat(playerId: String, text: String) = ArcBeat(text, setOf(playerId))

    private fun requirePlayer(state: ArcState, playerId: String) {
        if (playerId !in state.participantIds) throw ArcChoiceException("Unknown player $playerId")
    }
}