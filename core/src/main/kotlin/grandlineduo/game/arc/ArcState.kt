package grandlineduo.game.arc

enum class ArcArchetype {
    MARINE_OCCUPATION,
    UNDERWORLD_SMUGGLING,
    PIRATE_TYRANNY,
    RUINS_MYSTERY,
    ISLAND_CRISIS,
}

enum class ArcPhase {
    ARRIVAL,
    INVESTIGATION,
    ESCALATION,
    CLIMAX,
    AFTERMATH,
    COMPLETE,
}

data class ArcStartContext(
    val seed: Long,
    val islandId: String,
    val presentFactions: Set<String>,
    val worldFlags: Set<String>,
    val totalBounty: Long,
    val participantIds: Set<String> = setOf("p1", "p2"),
)

data class ArcChoice(val id: String, val label: String)

data class ArcView(
    val phase: ArcPhase,
    val title: String,
    val description: String,
    val choices: List<ArcChoice>,
)

data class ArcBeat(val text: String, val visibleTo: Set<String>)

data class ArcState(
    val arcId: String,
    val islandId: String,
    val seed: Long,
    val archetype: ArcArchetype,
    val phase: ArcPhase = ArcPhase.ARRIVAL,
    val sharedFlags: Set<String> = emptySet(),
    val privateClues: Map<String, Set<String>> = mapOf("p1" to emptySet(), "p2" to emptySet()),
    val actedThisPhase: Set<String> = emptySet(),
    val escalation: Int = 0,
) {
    /** Participants are encoded by the private-clue map keys to preserve the existing snapshot shape. */
    val participantIds: Set<String>
        get() = privateClues.keys
}

data class ArcOutcome(val state: ArcState, val beats: List<ArcBeat>) {
    fun beatsFor(playerId: String): List<ArcBeat> = beats.filter { playerId in it.visibleTo }
}

class ArcChoiceException(message: String) : IllegalArgumentException(message)