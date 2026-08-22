# Automatic Quest Objectives + Real HUNT Combat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace manual HUNT progress with three deterministic, host-authoritative PvE encounters whose victories advance only the bound contract through a reusable quest-objective event router.

**Architecture:** Keep `QuestEngine` as the quest-state owner, add a pure `QuestObjectiveRouter` for authoritative objective events, and add `QuestHuntFactory` + `QuestHuntCoordinator` for HUNT combat using the existing PvE `CombatEngine`. `StormglassGameplayCommandHandler` remains the source-routing boundary; HUNT uses QuestAction wire subtype 9 and persisted `quest.hunt.active`, so snapshot v11 and protocol v5 remain unchanged.

**Tech Stack:** Kotlin/JVM core, Android Kotlin app shell, custom deterministic test registry, existing `CombatEngine`, `HostReplica`/`ClientReplica`, durable snapshot/event log, real TCP LAN transport, Gradle Android build.

**Spec:** `docs/superpowers/specs/2026-08-22-quest-objective-events-hunt-design.md`

## Global Constraints

- Automate HUNT only. EXPLORE, COLLECT, RESCUE, ESCORT, and INVESTIGATE keep manual `PROGRESS` in this slice.
- `QuestEngine.progress(...)` rejects HUNT and BOSS. `QuestEngine.progressObjective(...)` accepts only ACTIVE HUNT.
- HUNT victories emit `QuestObjectiveEvent(sourceQuestId = questId)` so one contract cannot progress another contract sharing `targetId`.
- Generated HUNT takes exactly three victories: COMMON +1/3, RARE +2/6, EPIC +3/9, LEGENDARY +4/12.
- Enemy tiers: COMMON 48 HP / 9 ATK, RARE 72 / 12, EPIC 100 / 15, LEGENDARY 135 / 18.
- HUNT never restores HP/energy and victory never grants Berries, PEV, items, faction standing, bounty, loot, or other rewards. Existing `TURN_IN` remains the only reward point.
- HUNT defeat permanently fails the contract and preserves terminal hardcore consequences.
- Do not change `CombatEngine` formulas. Use `CombatModifierResolver.forWorld(...)` and existing power preparation.
- `START_HUNT` is host-authorized only from the hub-compatible state in the spec.
- Persist origin binding as `worldFlags["quest.hunt.active"] = questId`; no new snapshot structure.
- Snapshot stays v11, protocol stays v5, QuestAction stays subtype 9, existing subtype assignments stay unchanged.
- SOLO reuses existing companion combat planning; no HUNT-specific AI.
- TDD first: every production task gets an observed RED before implementation and an independently green checkpoint afterward.

---

## File Structure

**Create**
- `core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt`
- `core/src/main/kotlin/grandlineduo/game/quest/QuestHuntFactory.kt`
- `core/src/main/kotlin/grandlineduo/game/quest/QuestHuntCoordinator.kt`
- `core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt`
- `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntFactoryTest.kt`
- `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntCoordinatorTest.kt`
- `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntRoutingTest.kt`
- `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntLanIntegrationTest.kt`

**Modify**
- `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`
- `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`
- `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`
- `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`
- `docs/superpowers/plans/2026-08-22-quest-objective-events-hunt.md` only when recording real verification evidence.
- PR #4 description only after verification.

`GameSessionCoordinator.kt` and `MainActivity.kt` are expected to remain unchanged: generic QUEST dispatch already carries `START_HUNT`, while `submitCombatAction` and `submitPowerAction` already invoke the SOLO companion planner. Change them only if an observed RED proves a real deficiency.

---

### Task 1: Manual/Object Progress Split + QuestObjectiveRouter

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Produces:**

```kotlin
enum class QuestObjectiveEventType {
    ENEMY_DEFEATED,
    ISLAND_VISITED,
    ITEM_ACQUIRED,
    NPC_RESCUED,
    ESCORT_ARRIVED,
    CLUE_DISCOVERED,
}

data class QuestObjectiveEvent(
    val type: QuestObjectiveEventType,
    val targetId: String,
    val islandId: String,
    val amount: Int = 1,
    val sourceQuestId: String? = null,
)

object QuestObjectiveRouter {
    fun apply(world: WorldState, event: QuestObjectiveEvent): WorldState
}

object QuestEngine {
    fun progress(world: WorldState, questId: String, amount: Int): WorldState
    fun progressObjective(world: WorldState, questId: String, amount: Int): WorldState
}
```

- [ ] **Step 1: Write failing QuestEngine tests**

Add to `QuestEngineTest`:

```kotlin
test("manual quest progress rejects hunt while migration types still work") {
    val hunt = sampleQuest()
    val acceptedHunt = QuestEngine.accept(worldWithOffer(hunt), hunt.questId, "p1")
    assertTrue(runCatching { QuestEngine.progress(acceptedHunt, hunt.questId, 1) }.isFailure)

    listOf(QuestType.EXPLORE, QuestType.COLLECT, QuestType.RESCUE, QuestType.ESCORT, QuestType.INVESTIGATE)
        .forEach { type ->
            val quest = sampleQuest().copy(questId = "migration-${type.name}", type = type)
            val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")
            assertEquals(1, QuestEngine.progress(accepted, quest.questId, 1).questBoard.active.getValue(quest.questId).progress)
        }
}

test("objective progress advances active hunt and clamps at requirement") {
    val hunt = sampleQuest().copy(requiredAmount = 3)
    val accepted = QuestEngine.accept(worldWithOffer(hunt), hunt.questId, "p1")
    val advanced = QuestEngine.progressObjective(accepted, hunt.questId, 99)
    assertEquals(3, advanced.questBoard.active.getValue(hunt.questId).progress)
    assertEquals(QuestStatus.READY_TO_TURN_IN, advanced.questBoard.active.getValue(hunt.questId).status)
}
```

Also assert `progressObjective(...)` rejects BOSS, all five migration types, and READY_TO_TURN_IN HUNT.

- [ ] **Step 2: Write failing router tests and register them**

Create tests for exact cases:

```text
bound ENEMY_DEFEATED advances only sourceQuestId HUNT
same target second HUNT remains unchanged when sourceQuestId targets first
wrong source quest / target / island -> identical WorldState
READY_TO_TURN_IN -> identical WorldState
non-HUNT -> identical WorldState
ISLAND_VISITED/ITEM_ACQUIRED/NPC_RESCUED/ESCORT_ARRIVED/CLUE_DISCOVERED -> no-op
sourceQuestId null -> all exact semantic ACTIVE HUNT matches advance deterministically
```

For the last case build equivalent active maps in opposite insertion order and assert equal resulting worlds.

Register `QuestObjectiveRouterTest.register()`.

- [ ] **Step 3: Run RED**

```bash
bash tools/run-core-tests.sh
```

Expected: missing router/objective symbols and/or manual HUNT progress still accepted.

- [ ] **Step 4: Implement QuestEngine split**

Use one private advancement function but distinct public authorization:

```kotlin
fun progress(world: WorldState, questId: String, amount: Int): WorldState {
    require(amount > 0) { "Quest progress amount must be positive" }
    val current = world.questBoard.active[questId]
        ?: throw IllegalArgumentException("Quest is not active: $questId")
    require(current.status == QuestStatus.ACTIVE || current.status == QuestStatus.READY_TO_TURN_IN)
    require(current.definition.type != QuestType.BOSS && current.definition.type != QuestType.HUNT) {
        "${current.definition.type.name} contracts progress only through authoritative objectives"
    }
    if (current.status == QuestStatus.READY_TO_TURN_IN) return world
    return advance(world, questId, current, amount)
}

fun progressObjective(world: WorldState, questId: String, amount: Int): WorldState {
    require(amount > 0) { "Quest progress amount must be positive" }
    val current = world.questBoard.active[questId]
        ?: throw IllegalArgumentException("Quest is not active: $questId")
    require(current.status == QuestStatus.ACTIVE) { "Quest objective is not active" }
    require(current.definition.type == QuestType.HUNT) {
        "Objective progress is not enabled for ${current.definition.type.name}"
    }
    return advance(world, questId, current, amount)
}
```

`advance` keeps the current clamp and ACTIVE -> READY_TO_TURN_IN transition. Keep `completeBossObjective(...)` behavior unchanged.

- [ ] **Step 5: Implement router**

Validate event constructor: non-blank target/island, amount > 0, and non-blank sourceQuestId when non-null. Only ENEMY_DEFEATED acts in this slice.

```kotlin
if (event.type != QuestObjectiveEventType.ENEMY_DEFEATED) return world
var next = world
world.questBoard.active.toSortedMap().forEach { (questId, progress) ->
    val matches = progress.status == QuestStatus.ACTIVE &&
        progress.definition.type == QuestType.HUNT &&
        progress.definition.islandId == event.islandId &&
        progress.definition.targetId == event.targetId &&
        (event.sourceQuestId == null || event.sourceQuestId == questId)
    if (matches) next = QuestEngine.progressObjective(next, questId, event.amount)
}
return next
```

No substring matching.

- [ ] **Step 6: Run GREEN and commit**

```bash
bash tools/run-core-tests.sh
git add core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt \
        core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt \
        core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt \
        core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: route hunt progress through objective events"
```

---

### Task 2: Deterministic QuestHuntFactory

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestHuntFactory.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntFactoryTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Produces:**

```kotlin
object QuestHuntFactory {
    fun create(world: WorldState, progress: QuestProgress, campaignSeed: Long): CombatState
    fun progressPerVictory(rarity: QuestRarity): Int
    fun encounterIndex(progress: QuestProgress): Int
    fun combatSeed(progress: QuestProgress, campaignSeed: Long): Long
}
```

- [ ] **Step 1: Write failing factory tests**

Assert:

```text
identical inputs -> identical CombatState
COMMON=48/9, RARE=72/12, EPIC=100/15, LEGENDARY=135/18
wounded player HP/maxHp copied exactly, no heal
COMMON progress 0/1/2 -> encounter 1/2/3
RARE progress 0/2/4 -> encounter 1/2/3
stable enemy id <targetId>-hunt-<encounter>
non-HUNT, non-ACTIVE, wrong-island -> reject
encounter index changes combatSeed
```

Register the test object.

- [ ] **Step 2: Run RED**

`bash tools/run-core-tests.sh` -> missing `QuestHuntFactory`.

- [ ] **Step 3: Implement rarity math**

```kotlin
fun progressPerVictory(rarity: QuestRarity): Int = when (rarity) {
    QuestRarity.COMMON -> 1
    QuestRarity.RARE -> 2
    QuestRarity.EPIC -> 3
    QuestRarity.LEGENDARY -> 4
}

fun encounterIndex(progress: QuestProgress): Int =
    progress.progress / progressPerVictory(progress.definition.rarity) + 1
```

Stats are exactly 48/9, 72/12, 100/15, 135/18.

- [ ] **Step 4: Implement stable combat seed and factory**

```kotlin
fun combatSeed(progress: QuestProgress, campaignSeed: Long): Long {
    val q = progress.definition
    return campaignSeed xor
        (q.questId.hashCode().toLong() * 6364136223846793005L) xor
        (q.targetId.hashCode().toLong() * -7046029254386353131L) xor
        (q.rarity.ordinal.toLong() shl 41) xor
        (encounterIndex(progress).toLong() * 104729L)
}
```

Factory validates HUNT + ACTIVE + current island + p1/p2. It creates round 1 using current player HP/maxHP, enemy id `${targetId}-hunt-${encounterIndex}`, and telegraph target/type from `Random(combatSeed(...))` using the same two `nextBoolean()` pattern as `QuestBossFactory`.

- [ ] **Step 5: Run GREEN and commit**

```bash
bash tools/run-core-tests.sh
git add core/src/main/kotlin/grandlineduo/game/quest/QuestHuntFactory.kt \
        core/src/test/kotlin/grandlineduo/game/quest/QuestHuntFactoryTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: add deterministic hunt combat factory"
```

---

### Task 3: Host-Authoritative QuestHuntCoordinator

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestHuntCoordinator.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntCoordinatorTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Produces:**

```kotlin
class QuestHuntCoordinator(
    private val hostReplica: HostReplica,
    private val campaignSeed: Long,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    fun start(commandId: String, playerId: String, questId: String, hostTimestamp: Long): CampaignEvent
    fun submitAction(commandId: String, playerId: String, actionType: CombatActionType, hostTimestamp: Long): CampaignEvent
    fun submitPreparedAction(
        commandId: String,
        playerId: String,
        actionType: CombatActionType,
        preparedWorld: WorldState,
        sourceFingerprint: String,
        metadata: Map<String, String>,
        hostTimestamp: Long,
    ): CampaignEvent

    companion object { const val ACTIVE_QUEST_FLAG = "quest.hunt.active" }
}
```

- [ ] **Step 1: Write failing start-validation tests**

Create a hub fixture with both complete profiles, positive wounded HP, scenario COMPLETE (or arc COMPLETE), known resources, and one ACTIVE HUNT. Assert valid start creates `activeCombat` + binding with exact pre-start HP.

Assert rejection for non-HUNT, wrong island, READY status, missing profile, zero HP, structured activeCombat, restored legacy combat, voyage, duel, non-complete arc, non-complete scenario with no arc, existing HUNT binding, and existing BOSS binding.

- [ ] **Step 2: Add failing combat/outcome/metadata tests**

Assert first-lock/second-resolve, idempotent command retry, equipment modifiers, prepared power energy/use exactly once, exact rarity progress on only bound quest, no victory reward, three generated victories ending READY, hardcore defeat, invalid binding rejection, and exact metadata:

```text
start: meta.questAction=START_HUNT, meta.questId, meta.huntEncounter, meta.huntTarget
round: meta.huntQuestId, meta.huntEncounter, meta.roundResolved, meta.combatStatus
victory: meta.questObjective=ENEMY_DEFEATED, meta.questObjectiveSourceQuest, meta.questObjectiveTarget, meta.questObjectiveAmount, meta.questProgress
defeat: meta.questFailure=HUNT_DEFEAT
```

- [ ] **Step 3: Run RED**

`bash tools/run-core-tests.sh` -> missing coordinator.

- [ ] **Step 4: Implement host-authorized start**

Validate actor and hub state before factory creation:

```kotlin
require(playerId == "p1" || playerId == "p2") { "Unknown player $playerId" }
val restored = StormglassPersistenceAdapter.decode(world)
require(world.players["p1"]?.profile != null && world.players["p2"]?.profile != null)
require((world.players["p1"]?.hp ?: 0) > 0 && (world.players["p2"]?.hp ?: 0) > 0)
require(world.activeCombat == null && restored.combat == null)
require(world.activeVoyage == null)
require(world.activeDuel == null)
require(world.worldFlags[ACTIVE_QUEST_FLAG] == null)
require(world.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] == null)
require(world.activeArc == null || world.activeArc.phase == ArcPhase.COMPLETE)
if (world.activeArc == null) require(restored.scenario.stage == ScenarioStage.COMPLETE)
```

Validate active current-island HUNT, then commit factory combat + binding. Fingerprint: `quest-hunt-start|$playerId|$questId`.

Start metadata must be:

```kotlin
mapOf(
    "meta.questAction" to "START_HUNT",
    "meta.questId" to questId,
    "meta.huntEncounter" to QuestHuntFactory.encounterIndex(progress).toString(),
    "meta.huntTarget" to progress.definition.targetId,
)
```

- [ ] **Step 5: Implement action resolution and exact metadata**

Use the same `existing`, `commit`, and persistence structure as `QuestBossCoordinator`.

Basic action fingerprint: `quest-hunt-combat|$playerId|${actionType.name}`.

Resolve with:

```kotlin
CombatEngine(
    QuestHuntFactory.combatSeed(progress, campaignSeed),
    CombatModifierResolver.forWorld(sourceWorld),
)
```

Every action metadata map includes:

```kotlin
metadata["meta.huntQuestId"] = questId
metadata["meta.huntEncounter"] = QuestHuntFactory.encounterIndex(progress).toString()
```

Unresolved lock additionally records `meta.roundResolved=false` and current `meta.combatStatus`.

Resolved round records `meta.roundResolved=true`, existing combat log/combo/damage/status fields, and synchronized player HP.

On VICTORY:

```kotlin
val cleared = sourceWorld.copy(
    players = syncedPlayers,
    activeCombat = null,
    worldFlags = sourceWorld.worldFlags - ACTIVE_QUEST_FLAG,
)
val amount = QuestHuntFactory.progressPerVictory(progress.definition.rarity)
val next = QuestObjectiveRouter.apply(
    cleared,
    QuestObjectiveEvent(
        QuestObjectiveEventType.ENEMY_DEFEATED,
        progress.definition.targetId,
        progress.definition.islandId,
        amount,
        sourceQuestId = questId,
    ),
)
metadata["meta.questObjective"] = "ENEMY_DEFEATED"
metadata["meta.questObjectiveSourceQuest"] = questId
metadata["meta.questObjectiveTarget"] = progress.definition.targetId
metadata["meta.questObjectiveAmount"] = amount.toString()
metadata["meta.questProgress"] = next.questBoard.active.getValue(questId).progress.toString()
```

On DEFEAT: synchronize HP, keep `result.state` terminal combat, clear binding, then `QuestEngine.fail(..., "hunt defeat")`, and set `metadata["meta.questFailure"] = "HUNT_DEFEAT"`.

Do not heal or reward.

- [ ] **Step 6: Implement prepared powers atomically**

Mirror `QuestBossCoordinator.submitPreparedAction`: reuse original `sourceFingerprint`, validate prepared campaign id, and resolve against `preparedWorld` so energy/mastery and combat action commit once.

- [ ] **Step 7: Run GREEN and commit**

```bash
bash tools/run-core-tests.sh
git add core/src/main/kotlin/grandlineduo/game/quest/QuestHuntCoordinator.kt \
        core/src/test/kotlin/grandlineduo/game/quest/QuestHuntCoordinatorTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: add authoritative hunt combat lifecycle"
```

---

### Task 4: Handler Routing + Combat Source Exclusivity

**Files:**
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntRoutingTest.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

- [ ] **Step 1: Write failing routing tests and register them**

Through `StormglassGameplayCommandHandler`, assert START_HUNT routing, manual HUNT PROGRESS rejection, HUNT-bound basic action, HUNT-bound prepared power, HUNT+BOSS conflict rejection, existing START_BOSS, existing arc route, and existing legacy scenario route.

- [ ] **Step 2: Run RED**

`bash tools/run-core-tests.sh` -> START_HUNT unknown and bound HUNT combat falls through.

- [ ] **Step 3: Instantiate and route QuestHuntCoordinator**

```kotlin
private val questHuntCoordinator = QuestHuntCoordinator(
    hostReplica = hostReplica,
    campaignSeed = seed,
    snapshotStore = snapshotStore,
    durableStore = durableStore,
)
```

QuestAction order: START_BOSS -> existing boss; START_HUNT -> hunt; other -> existing quest management.

- [ ] **Step 4: Route structured CombatAction by binding**

```kotlin
val huntBound = before.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG] != null
val bossBound = before.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] != null
require(!(huntBound && bossBound)) { "Invalid simultaneous HUNT and BOSS combat bindings" }
```

HUNT -> hunt coordinator, BOSS -> boss coordinator, otherwise -> existing arc coordinator.

- [ ] **Step 5: Route PowerAction preserving duel priority**

Keep `PowerTechniqueEngine.prepare(...)` first and active-duel routing first. On `poweredWorld`, reject simultaneous HUNT+BOSS, route HUNT prepared action, then existing BOSS, then current arc/scenario.

- [ ] **Step 6: Run GREEN and commit**

```bash
bash tools/run-core-tests.sh
git add core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt \
        core/src/test/kotlin/grandlineduo/game/quest/QuestHuntRoutingTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: route hunt combat through host authority"
```

---

### Task 5: Quest Presenter + Existing SOLO Planner

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`

- [ ] **Step 1: Write failing presenter tests**

Assert ACTIVE HUNT progress 0 -> START_HUNT labeled `Rastrear e enfrentar alvo`; progress >0 -> `Continuar caçada`; no HUNT PROGRESS; READY -> TURN_IN; BOSS retains START_BOSS; all five migration types retain PROGRESS.

- [ ] **Step 2: Write failing SOLO session test using existing durable-fixture pattern**

Mirror `solo quest boss enters combat and existing companion planner resolves p2 action`:

1. Temp root; campaign `session-quest-hunt`.
2. Build p1/p2 profiles with `CharacterCreation.create(validDraft(...))`.
3. COMMON HUNT on stormglass-cay, required 3, ACTIVE progress 0.
4. Build WorldState with both profiles, mode SOLO, chapter 0 and active HUNT. Obtain base scenario using `StormglassPersistenceAdapter.decode(initial).scenario`, copy to `ScenarioStage.COMPLETE` with empty `actedThisStage`, then `StormglassPersistenceAdapter.encode(initial, completedScenario, null)`.
5. Initialize `DurableCampaignStore` with that encoded world.
6. `session.resume(campaignId)`.
7. `session.submitQuestAction("START_HUNT", hunt.questId)` -> COMBAT presentation.
8. Record round/resources, then `session.submitCombatAction(CombatActionType.SETUP)`.
9. Assert existing planner supplied P2: combat null after victory or round advanced; reject a P1-only locked state caused by absent HUNT AI integration.
10. Assert START_HUNT itself did not heal HP/energy or grant quest reward.

No new public session API.

- [ ] **Step 3: Run RED**

`bash tools/run-core-tests.sh` -> presenter/manual HUNT and START_HUNT behavior fail as expected.

- [ ] **Step 4: Implement presenter migration**

```kotlin
when (progress.definition.type) {
    QuestType.BOSS -> if (world.activeCombat == null) {
        add(GameAction("START_BOSS|${progress.definition.questId}|1", "Enfrentar alvo • ${progress.definition.title}", "QUEST"))
    }
    QuestType.HUNT -> if (world.activeCombat == null) {
        val prefix = if (progress.progress == 0) "Rastrear e enfrentar alvo" else "Continuar caçada"
        add(GameAction("START_HUNT|${progress.definition.questId}|1", "$prefix • ${progress.definition.title}", "QUEST"))
    }
    else -> add(GameAction("PROGRESS|${progress.definition.questId}|1", "Registrar progresso • ${progress.definition.title}", "QUEST"))
}
```

No new GameScreen or Android action kind.

- [ ] **Step 5: Verify SOLO planner without HUNT-specific AI**

Existing `submitCombatAction` already calls `autoPlayCompanion()` for structured combat. If RED remains, use systematic-debugging and fix only a proven generic-planner deficiency; never branch AI on quest type.

- [ ] **Step 6: Run GREEN and commit**

```bash
bash tools/run-core-tests.sh
git add core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt \
        core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt \
        core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt
git commit -m "feat: expose real hunt encounters in quest ui"
```

If a generic session production fix is proven necessary, add only that exact production file.

---

### Task 6: Real TCP HUNT Reconnect + Convergence

**Files:**
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntLanIntegrationTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

- [ ] **Step 1: Write and register one real TCP lifecycle test**

1. HOST_COOP hub world, two complete wounded characters, known resources, one ACTIVE HUNT and a second same-target HUNT that must not progress.
2. Start `LanHostServer` + handler.
3. Connect P2 replica over loopback TCP.
4. P2 START_HUNT; refresh; assert combat/binding/enemy/hash convergence.
5. P1 locks SETUP; refresh P2 and store its confirmed state.
6. Disconnect P2.
7. Recreate fresh P2 from stale confirmed state and reconnect.
8. Refresh; assert binding, enemy id, round, locked P1 action, HP and canonical hash.
9. P2 FINISHER; continue real wire combat until VICTORY.
10. Assert combat/binding clear, exact bound progress, unrelated same-target HUNT unchanged.
11. Assert Berries/bounties/inventory/reward flags unchanged.
12. Start/finish encounters 2 and 3 through real wire commands; final victory -> READY_TO_TURN_IN.
13. TURN_IN once -> existing reward exactly once.
14. Resend exact TURN_IN command id -> no duplicate mutation.

Only encounter 1 requires forced disconnect; encounters 2/3 still use authoritative TCP commands.

- [ ] **Step 2: Run full suite**

```bash
bash tools/run-core-tests.sh
```

If reconnect/source binding fails, use systematic-debugging, add the smallest focused assertion, fix root cause and rerun.

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/grandlineduo/game/quest/QuestHuntLanIntegrationTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "test: cover hunt quest reconnect over real tcp"
```

Include a production file only if a verified reconnect bug required it.

---

### Task 7: Full Regression + Android + PR Evidence

**Files:**
- Modify plan only with observed verification values.
- Update PR #4 only with observed HUNT evidence.
- Temporary Android workflow allowed only when local build is unavailable, and must be removed afterward.

- [ ] **Step 1: Read verification-before-completion and run exact-head core suite**

```bash
bash tools/run-core-tests.sh
```

Require `RESULT N/N passed`, zero failures. Inspect objective routing, manual HUNT rejection, factory, coordinator, three victories, defeat, powers/idempotency, handler routing, START_BOSS regression, presenter, SOLO, TCP HUNT, quest boss, duel, arc, persistence and LAN.

- [ ] **Step 2: Verify compatibility invariants**

```text
CombatEngine.kt unchanged
QuestBossFactory.kt unchanged
QuestBossCoordinator.kt unchanged
PROTOCOL_VERSION == 5
QuestAction == subtype 9
WorldStateCodec CURRENT_VERSION == 11
no new canonical/snapshot block
five migration types still accept manual PROGRESS
HUNT victory has no reward before TURN_IN
```

- [ ] **Step 3: Build Android on same source**

```bash
gradle --no-daemon --stacktrace :app:assembleDebug
test -s app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

If local build is unavailable, use a temporary PR workflow running core + assembleDebug + non-empty APK + SHA-256. Remove it after success and compare build-tested source to clean final head to prove no post-build app/core changes.

- [ ] **Step 4: Record only observed evidence**

Record final feature head, PR merge SHA, exact N/N, CI run/job, Android run/job, BUILD SUCCESSFUL, APK SHA-256, and post-build diff proof when applicable. Never guess values.

- [ ] **Step 5: Update PR #4 but do not merge**

Add objective events, three encounters, hardcore/no-heal, Haki/Akuma/equipment reuse, SOLO reuse, TCP reconnect, compatibility, exact tests and APK hash. Keep PR open.

- [ ] **Step 6: Commit verification notes and gate exact final head**

```bash
git add docs/superpowers/plans/2026-08-22-quest-objective-events-hunt.md
git commit -m "docs: record hunt quest verification"
```

Only commit if notes changed. Then check/run Core CI on that exact head before any completion claim.
