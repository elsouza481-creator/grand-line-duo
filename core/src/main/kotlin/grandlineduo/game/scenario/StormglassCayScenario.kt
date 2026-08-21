package grandlineduo.game.scenario

class StormglassCayScenario {
    fun initialState(participantIds: Set<String> = LEGACY_PARTICIPANTS): ScenarioState {
        val participants = validateParticipants(participantIds)
        return ScenarioState(
            participantIds = participants,
            privateKnowledge = participants.associateWith { emptySet() },
        )
    }

    fun view(state: ScenarioState, playerId: String): ScenarioView {
        requirePlayer(state, playerId)
        val leadRole = playerId == "p1" || playerId == "p3"
        val choices = when (state.stage) {
            ScenarioStage.ARRIVAL -> if (leadRole) {
                listOf(
                    ScenarioChoice("help_dockworker", "Ajudar o estivador ferido"),
                    ScenarioChoice("visit_tavern", "Entrar na Taverna da Âncora Quebrada"),
                )
            } else {
                listOf(
                    ScenarioChoice("shadow_courier", "Seguir discretamente o mensageiro da Marinha"),
                    ScenarioChoice("inspect_market", "Investigar o mercado coberto"),
                )
            }

            ScenarioStage.INVESTIGATION -> if (leadRole) {
                buildList {
                    if ("dockworker_saved" in state.sharedFlags) {
                        add(ScenarioChoice("question_dockworker", "Perguntar ao estivador sobre o armazém"))
                    }
                    add(ScenarioChoice("search_rumors", "Cruzar rumores sobre a carga desaparecida"))
                }
            } else {
                buildList {
                    if ("marine_manifest" in state.privateKnowledge[playerId].orEmpty()) {
                        add(ScenarioChoice("reveal_manifest", "Mostrar o manifesto secreto à tripulação"))
                        add(ScenarioChoice("keep_manifest_secret", "Guardar o manifesto em segredo"))
                    }
                    add(ScenarioChoice("inspect_rooftops", "Observar o armazém pelos telhados"))
                }
            }

            ScenarioStage.WAREHOUSE -> listOf(
                ScenarioChoice("enter_warehouse", "Entrar no armazém"),
                ScenarioChoice("set_ambush", "Preparar uma emboscada"),
            )

            ScenarioStage.MINIBOSS -> emptyList()
            ScenarioStage.RETURN_TO_SHIP -> listOf(
                ScenarioChoice("return_to_ship", "Voltar ao navio antes da chegada dos reforços"),
            )
            ScenarioStage.COMPLETE -> emptyList()
        }
        return ScenarioView(
            stage = state.stage,
            title = when (state.stage) {
                ScenarioStage.ARRIVAL -> "Porto de Stormglass Cay"
                ScenarioStage.INVESTIGATION -> "Sombras sobre o porto"
                ScenarioStage.WAREHOUSE -> "Armazém 7"
                ScenarioStage.MINIBOSS -> "Capitão Veyron"
                ScenarioStage.RETURN_TO_SHIP -> "Fuga pelo cais"
                ScenarioStage.COMPLETE -> "Rota aberta"
            },
            description = descriptionFor(state.stage, playerId),
            choices = choices,
        )
    }

    fun choose(state: ScenarioState, playerId: String, choiceId: String): ScenarioOutcome {
        requirePlayer(state, playerId)
        if (playerId in state.actedThisStage) {
            throw ScenarioChoiceException("$playerId already acted in ${state.stage}")
        }
        val allowed = view(state, playerId).choices.map { it.id }.toSet()
        if (choiceId !in allowed) throw ScenarioChoiceException("Choice $choiceId is not available to $playerId")

        var shared = state.sharedFlags
        var privateKnowledge = state.privateKnowledge
        val beats = mutableListOf<NarrativeBeat>()

        when (choiceId) {
            "help_dockworker" -> {
                shared = shared + "dockworker_saved"
                beats += sharedBeat(state.participantIds, "Vocês estabilizam o estivador. Antes de partir, ele aponta para o Armazém 7 e sussurra que a Marinha está escondendo uma carga apreendida.")
            }
            "visit_tavern" -> {
                shared = shared + "tavern_rumor"
                beats += privateBeat(playerId, "Na taverna, um carpinteiro menciona homens armados descarregando caixas sem selo no Armazém 7.")
            }
            "shadow_courier" -> {
                privateKnowledge = addPrivate(privateKnowledge, playerId, "marine_manifest")
                beats += privateBeat(playerId, "Você intercepta por alguns segundos um manifesto da Marinha: a carga do Armazém 7 inclui um Log Pose confiscado e será removida ao anoitecer.")
            }
            "inspect_market" -> {
                privateKnowledge = addPrivate(privateKnowledge, playerId, "warehouse_guard_shift")
                beats += privateBeat(playerId, "No mercado, você percebe a troca de turno dos guardas e descobre uma janela curta de acesso ao Armazém 7.")
            }
            "question_dockworker" -> {
                shared = shared + "warehouse_side_door"
                beats += sharedBeat(state.participantIds, "O estivador revela uma porta lateral usada pelos carregadores. É uma entrada melhor do que o portão principal.")
            }
            "search_rumors" -> {
                shared = shared + "captain_veyron_named"
                beats += sharedBeat(state.participantIds, "Os rumores convergem para um nome: Capitão Veyron, oficial responsável pela apreensão.")
            }
            "reveal_manifest" -> {
                shared = shared + "manifest_revealed"
                beats += sharedBeat(state.participantIds, "O manifesto é revelado à tripulação: o objetivo real é recuperar o Log Pose antes que a carga seja transferida.")
            }
            "keep_manifest_secret" -> {
                privateKnowledge = addPrivate(privateKnowledge, playerId, "manifest_kept_secret")
                beats += privateBeat(playerId, "Você dobra o manifesto e decide não revelar o conteúdo ainda.")
            }
            "inspect_rooftops" -> {
                privateKnowledge = addPrivate(privateKnowledge, playerId, "roof_entry")
                beats += privateBeat(playerId, "Dos telhados, você encontra uma claraboia diretamente sobre o setor de carga.")
            }
            "enter_warehouse" -> {
                shared = shared + "warehouse_entered"
                beats += sharedBeat(state.participantIds, "Vocês cruzam a entrada do Armazém 7. Passos pesados ecoam entre as caixas.")
            }
            "set_ambush" -> {
                shared = shared + "ambush_prepared"
                beats += sharedBeat(state.participantIds, "Vocês transformam cordas, roldanas e caixas em uma armadilha improvisada antes de avançar.")
            }
            "return_to_ship" -> beats += sharedBeat(state.participantIds, "Com a sirene da Marinha ao fundo, vocês correm pelo cais e alcançam o navio.")
        }

        var next = state.copy(
            sharedFlags = shared,
            privateKnowledge = privateKnowledge,
            actedThisStage = state.actedThisStage + playerId,
        )
        next = advanceIfReady(next)
        return ScenarioOutcome(next, beats)
    }

    fun markMinibossDefeated(state: ScenarioState): ScenarioState {
        if (state.stage != ScenarioStage.MINIBOSS) {
            throw ScenarioChoiceException("Miniboss is not active")
        }
        return state.copy(
            stage = ScenarioStage.RETURN_TO_SHIP,
            sharedFlags = state.sharedFlags + setOf("captain_veyron_defeated", "log_pose_recovered"),
            actedThisStage = emptySet(),
        )
    }

    private fun advanceIfReady(state: ScenarioState): ScenarioState {
        if (state.actedThisStage != state.participantIds) return state
        val nextStage = when (state.stage) {
            ScenarioStage.ARRIVAL -> ScenarioStage.INVESTIGATION
            ScenarioStage.INVESTIGATION -> ScenarioStage.WAREHOUSE
            ScenarioStage.WAREHOUSE -> ScenarioStage.MINIBOSS
            ScenarioStage.RETURN_TO_SHIP -> ScenarioStage.COMPLETE
            else -> state.stage
        }
        return if (nextStage == state.stage) state else state.copy(stage = nextStage, actedThisStage = emptySet())
    }

    private fun addPrivate(
        current: Map<String, Set<String>>,
        playerId: String,
        value: String,
    ): Map<String, Set<String>> = current + (playerId to (current[playerId].orEmpty() + value))

    private fun sharedBeat(participantIds: Set<String>, text: String) = NarrativeBeat(text, participantIds)
    private fun privateBeat(playerId: String, text: String) = NarrativeBeat(text, setOf(playerId))

    private fun requirePlayer(state: ScenarioState, playerId: String) {
        if (playerId !in state.participantIds) throw ScenarioChoiceException("Unknown player $playerId")
    }

    private fun validateParticipants(participantIds: Set<String>): Set<String> {
        val participants = participantIds.toSortedSet()
        if (participants.size !in 2..4 || participants.any { it !in HUMAN_PLAYER_IDS }) {
            throw ScenarioChoiceException("Stormglass requires two to four human participants")
        }
        if ("p1" !in participants || "p2" !in participants) {
            throw ScenarioChoiceException("Stormglass requires P1 and P2")
        }
        return participants
    }

    private fun descriptionFor(stage: ScenarioStage, playerId: String): String = when (stage) {
        ScenarioStage.ARRIVAL -> "Chuva salgada cobre o porto. Sinos da Marinha soam além dos telhados de cobre. $playerId precisa escolher por onde começar."
        ScenarioStage.INVESTIGATION -> "As pistas apontam para um carregamento apreendido que desaparecerá ao anoitecer."
        ScenarioStage.WAREHOUSE -> "O Armazém 7 está à frente; o próximo passo define como o confronto começa."
        ScenarioStage.MINIBOSS -> "O Capitão Veyron fecha a saída e leva a mão ao sabre coberto por Busoshoku."
        ScenarioStage.RETURN_TO_SHIP -> "Reforços se aproximam. O Log Pose está com vocês, mas a ilha entrou em alerta."
        ScenarioStage.COMPLETE -> "Stormglass Cay fica para trás enquanto uma nova rota se estabiliza no Log Pose."
    }

    companion object {
        val LEGACY_PARTICIPANTS: Set<String> = setOf("p1", "p2")
        private val HUMAN_PLAYER_IDS: Set<String> = setOf("p1", "p2", "p3", "p4")
    }
}
