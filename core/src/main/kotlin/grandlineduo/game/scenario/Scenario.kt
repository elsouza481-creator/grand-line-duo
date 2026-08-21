package grandlineduo.game.scenario

enum class ScenarioStage {
    ARRIVAL,
    INVESTIGATION,
    WAREHOUSE,
    MINIBOSS,
    RETURN_TO_SHIP,
    COMPLETE,
}

data class ScenarioChoice(val id: String, val label: String)

data class ScenarioView(
    val stage: ScenarioStage,
    val title: String,
    val description: String,
    val choices: List<ScenarioChoice>,
)

data class NarrativeBeat(
    val text: String,
    val visibleTo: Set<String>,
)

data class ScenarioState(
    val stage: ScenarioStage = ScenarioStage.ARRIVAL,
    val sharedFlags: Set<String> = emptySet(),
    val privateKnowledge: Map<String, Set<String>> = mapOf("p1" to emptySet(), "p2" to emptySet()),
    val actedThisStage: Set<String> = emptySet(),
    val participantIds: Set<String> = setOf("p1", "p2"),
)

data class ScenarioOutcome(
    val state: ScenarioState,
    val beats: List<NarrativeBeat>,
) {
    fun beatsFor(playerId: String): List<NarrativeBeat> = beats.filter { playerId in it.visibleTo }
}

class ScenarioChoiceException(message: String) : RuntimeException(message)
