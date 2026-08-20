package grandlineduo.game

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.game.combat.*
import grandlineduo.game.director.*
import grandlineduo.game.scenario.ScenarioOutcome
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.game.scenario.StormglassCayScenario
import java.nio.file.Path

data class StormglassVerticalSliceResult(
    val world: WorldState,
    val scenario: ScenarioState,
    val finalCombatStatus: CombatStatus,
    val privateKnowledgeP2: Set<String>,
    val directorDecision: DirectorDecision,
    val coopCombos: Int,
    val transcript: List<String>,
)

object StormglassVerticalSlice {
    fun run(seed: Long, saveDirectory: Path): StormglassVerticalSliceResult {
        val snapshotStore = SnapshotStore(saveDirectory)
        val scenarioEngine = StormglassCayScenario()
        val transcript = mutableListOf<String>()
        var world = WorldState(
            campaignId = "stormglass-demo-$seed",
            islandId = "stormglass-cay",
            players = mapOf(
                "p1" to PlayerState("p1", "Kairo", 60, 60, 0),
                "p2" to PlayerState("p2", "Namiya", 55, 55, 0),
            ),
        )
        var scenario = scenarioEngine.initialState()

        fun apply(outcome: ScenarioOutcome) {
            scenario = outcome.state
            outcome.beats.forEach { beat ->
                transcript += "[${beat.visibleTo.sorted().joinToString("+")}] ${beat.text}"
            }
        }

        apply(scenarioEngine.choose(scenario, "p1", "help_dockworker"))
        apply(scenarioEngine.choose(scenario, "p2", "shadow_courier"))

        // Autosave immediately after the private discovery, then reload from disk.
        world = StormglassPersistenceAdapter.encode(world, scenario, null)
        snapshotStore.save(world)
        world = snapshotStore.loadLatestValid() ?: error("Autosave reload failed")
        scenario = StormglassPersistenceAdapter.decode(world).scenario

        apply(scenarioEngine.choose(scenario, "p1", "question_dockworker"))
        apply(scenarioEngine.choose(scenario, "p2", "reveal_manifest"))

        val director = GrandLineDirector(
            listOf(
                DirectorEvent(
                    id = "security-lockdown",
                    title = "Capitão Veyron fecha o Armazém 7",
                    kind = DirectorEventKind.THREAT,
                    threatCost = 3,
                    requiredFaction = "MARINES",
                    requiredFlag = "manifest_revealed",
                ),
                DirectorEvent(
                    id = "battleship-arrives",
                    title = "Um encouraçado impossível chega ao porto",
                    kind = DirectorEventKind.THREAT,
                    threatCost = 99,
                    requiredFaction = "MARINES",
                ),
            )
        )
        val totalHp = world.players.values.sumOf { it.hp }
        val maxHp = world.players.values.sumOf { it.maxHp }
        val decision = director.choose(
            DirectorContext(
                seed = seed,
                decisionIndex = 1,
                islandId = world.islandId,
                difficulty = DirectorDifficulty.NORMAL,
                totalBounty = world.players.values.sumOf { it.bounty },
                currentPartyHp = totalHp,
                maxPartyHp = maxHp,
                presentFactions = setOf("MARINES", "UNDERWORLD"),
                worldFlags = scenario.sharedFlags,
                recentEventIds = emptyList(),
            )
        ) ?: error("Director produced no event")
        transcript += "Director: ${decision.event.title} (budget ${decision.threatBudget}, cost ${decision.event.threatCost})"

        apply(scenarioEngine.choose(scenario, "p1", "set_ambush"))
        apply(scenarioEngine.choose(scenario, "p2", "enter_warehouse"))

        val combatEngine = CombatEngine(seed)
        var combat = CombatState(
            round = 1,
            players = world.players.mapValues { (id, player) ->
                Combatant(id, player.name, player.hp, player.maxHp)
            },
            enemy = EnemyCombatant(
                id = "veyron",
                name = "Capitão Veyron",
                hp = if ("ambush_prepared" in scenario.sharedFlags) 105 else 120,
                maxHp = 120,
                attackPower = 18,
            ),
            telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
        )

        var coopCombos = 0
        var firstRound = true
        while (combat.status == CombatStatus.ACTIVE) {
            combat = combatEngine.lockAction(combat, CombatAction("p1", CombatActionType.SETUP))

            if (firstRound) {
                // Persist an intentionally half-locked simultaneous round, then restore it.
                world = StormglassPersistenceAdapter.encode(world, scenario, combat)
                snapshotStore.save(world)
                world = snapshotStore.loadLatestValid() ?: error("Combat autosave reload failed")
                val restored = StormglassPersistenceAdapter.decode(world)
                scenario = restored.scenario
                combat = restored.combat ?: error("Combat missing after restore")
                firstRound = false
            }

            combat = combatEngine.lockAction(combat, CombatAction("p2", CombatActionType.FINISHER))
            val round = combatEngine.resolveIfReady(combat) ?: error("Both combat actions should be locked")
            combat = round.state
            if (round.coopCombo) coopCombos++
            transcript += round.log

            world = StormglassPersistenceAdapter.encode(world, scenario, combat)
            snapshotStore.save(world)
            if (combat.round > 10 && combat.status == CombatStatus.ACTIVE) {
                error("Combat exceeded safety round limit")
            }
        }
        check(combat.status == CombatStatus.VICTORY) { "Vertical slice miniboss was not defeated" }

        scenario = scenarioEngine.markMinibossDefeated(scenario)
        apply(scenarioEngine.choose(scenario, "p1", "return_to_ship"))
        apply(scenarioEngine.choose(scenario, "p2", "return_to_ship"))

        val rewardedPlayers = world.players.mapValues { (id, player) ->
            val increase = if (id == "p1") 1_200_000L else 800_000L
            player.copy(bounty = player.bounty + increase)
        }
        world = world.copy(
            partyBerries = 250_000,
            players = rewardedPlayers,
            worldFlags = world.worldFlags + mapOf(
                "stormglass_completed" to "true",
                "marines_alerted" to "true",
            ),
        )
        world = StormglassPersistenceAdapter.encode(world, scenario, null)
        snapshotStore.save(world)
        world = snapshotStore.loadLatestValid() ?: error("Final autosave reload failed")

        return StormglassVerticalSliceResult(
            world = world,
            scenario = scenario,
            finalCombatStatus = combat.status,
            privateKnowledgeP2 = scenario.privateKnowledge["p2"].orEmpty(),
            directorDecision = decision,
            coopCombos = coopCombos,
            transcript = transcript,
        )
    }
}
