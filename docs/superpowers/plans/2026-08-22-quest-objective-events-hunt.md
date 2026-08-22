# Automatic Quest Objectives + Real HUNT Combat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace manual HUNT progress with three deterministic, host-authoritative PvE encounters whose victories advance only the bound contract through a reusable quest-objective event router.

**Architecture:** Keep `QuestEngine` as the quest-state owner, add a pure `QuestObjectiveRouter` for authoritative objective events, and add `QuestHuntFactory` + `QuestHuntCoordinator` for HUNT combat using the existing PvE `CombatEngine`. `StormglassGameplayCommandHandler` remains the source-routing boundary; HUNT uses the existing QuestAction wire subtype 9 and a persisted `quest.hunt.active` origin binding, so snapshot v11 and protocol v5 stay unchanged.

**Tech Stack:** Kotlin/JVM core, Android Kotlin app shell, custom deterministic test registry, existing `CombatEngine`, `HostReplica`/`ClientReplica`, durable snapshots/event log, real TCP LAN transport, Gradle Android build.

**Spec:** `docs/superpowers/specs/2026-08-22-quest-objective-events-hunt-design.md`

## Global Constraints

- Only HUNT is automated in this slice; EXPLORE, COLLECT, RESCUE, ESCORT, and INVESTIGATE keep manual `PROGRESS` until their real event integrations exist.
- `QuestEngine.progress(...)` rejects HUNT and BOSS; `QuestEngine.progressObjective(...)` accepts only ACTIVE HUNT in this slice.
- HUNT victories use bound `QuestObjectiveEvent(sourceQuestId = questId)` so one contract cannot progress another contract sharing the same `targetId`.
- Generated HUNT contracts take exactly three successful encounters: COMMON +1 of 3, RARE +2 of 6, EPIC +3 of 9, LEGENDARY +4 of 12.
- HUNT enemy tiers are exactly COMMON 48 HP / 9 ATK, RARE 72 / 12, EPIC 100 / 15, LEGENDARY 135 / 18.
- Starting or winning HUNT never restores HP/energy and never grants Berries, PEV, items, faction standing, bounty, loot, or other rewards; existing `TURN_IN` remains the reward point.
- HUNT defeat permanently fails the contract and preserves terminal hardcore combat consequences.
- Existing `CombatEngine` formulas remain unchanged; HUNT uses `CombatModifierResolver.forWorld(...)` and existing PowerTechnique preparation.
- `START_HUNT` is valid only from the host-validated hub-compatible state in the spec.
- Persist origin binding as `worldFlags["quest.hunt.active"] = questId`; do not add structured snapshot state.
- Snapshot remains v11; protocol remains v5; QuestAction remains wire subtype 9; no existing subtype is renumbered.
- SOLO reuses the existing companion combat planner; no new AI implementation.
- Production work is TDD-first with explicit RED before implementation and independent GREEN checkpoints.

---

## File Structure

**Create**
- `core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt` — typed transient objective event + deterministic matching.
- `core/src/main/kotlin/grandlineduo/game/quest/QuestHuntFactory.kt` — deterministic HUNT encounter construction and rarity math.
- `core/src/main/kotlin/grandlineduo/game/quest/QuestHuntCoordinator.kt` — host-authoritative HUNT lifecycle/combat/persistence.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt`
- `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntFactoryTest.kt`
- `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntCoordinatorTest.kt`
- `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntLanIntegrationTest.kt`

**Modify**
- `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt` — split manual/objective progress APIs.
- `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt` — START_HUNT + combat/power source routing.
- `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt` — HUNT actions/labels, no manual HUNT progress.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- `core/src/test/kotlin/grandlineduo/game/quest/QuestBossCoordinatorTest.kt` only if a routing regression requires an explicit existing-boss invariant; do not change boss production behavior.
- `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`
- `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`
- `docs/superpowers/plans/2026-08-22-quest-objective-events-hunt.md` only at final verification to record concrete evidence.
- PR #4 description at final verification.

`GameSessionCoordinator.kt` and `MainActivity.kt` should not need production changes: generic QUEST dispatch already carries `START_HUNT`, and existing `submitCombatAction`/`submitPowerAction` already trigger the SOLO companion planner. Modify them only if a failing integration test proves the existing path is insufficient.

---

### Task 1: Separate Manual Progress and Add Objective Router

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Produces:

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

- [ ] **Step 1: Write failing QuestEngine migration tests**

Extend `QuestEngineTest.register()` with concrete cases:

```kotlin
test("manual quest progress rejects hunt while migration types still work") {
    val hunt = sampleQuest()
    val acceptedHunt = QuestEngine.accept(worldWithOffer(hunt), hunt.questId, "p1")
    assertTrue(runCatching { QuestEngine.progress(acceptedHunt, hunt.questId, 1) }.isFailure)

    listOf(
        QuestType.EXPLORE,
        QuestType.COLLECT,
        QuestType.RESCUE,
        QuestType.ESCORT,
        QuestType.INVESTIGATE,
    ).forEach { type ->
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

Also assert `progressObjective(...)` rejects BOSS and each of the five migration types.

- [ ] **Step 2: Write failing router tests and register them**

Create `QuestObjectiveRouterTest` with tests proving:

```text
matching bound ENEMY_DEFEATED -> only sourceQuestId HUNT advances
same target second HUNT -> unchanged when sourceQuestId points to first
wrong sourceQuestId -> no mutation
wrong target -> no mutation
wrong island -> no mutation
READY_TO_TURN_IN -> no mutation
non-HUNT -> no mutation
future event types -> no mutation
sourceQuestId null -> all exact semantic matches advance in sorted quest-id order
```

For deterministic multi-match, construct two ACTIVE HUNT entries in reverse insertion order and assert the resulting `WorldState` equals the result from sorted insertion order.

Register `grandlineduo.game.quest.QuestObjectiveRouterTest.register()` in `TestRunner`.

- [ ] **Step 3: Run RED**

Run:

```bash
bash tools/run-core-tests.sh
```

Expected: compile/test failure because `QuestObjectiveEvent`, `QuestObjectiveRouter`, and `QuestEngine.progressObjective` do not exist and existing manual HUNT progress still succeeds.

- [ ] **Step 4: Implement minimal QuestEngine split**

Refactor only the advancement core:

```kotlin
fun progress(world: WorldState, questId: String, amount: Int): WorldState {
    val current = requireProgressable(world, questId, amount)
    require(current.definition.type != QuestType.BOSS && current.definition.type != QuestType.HUNT) {
        "${current.definition.type.name} contracts progress only through authoritative objectives"
    }
    return advance(world, questId, current, amount)
}

fun progressObjective(world: WorldState, questId: String, amount: Int): WorldState {
    val current = requireProgressable(world, questId, amount)
    require(current.definition.type == QuestType.HUNT) {
        "Objective progress is not enabled for ${current.definition.type.name}"
    }
    return advance(world, questId, current, amount)
}
```

`requireProgressable` must require positive amount, active quest lookup, and status ACTIVE or READY; READY returns unchanged from `advance`. `advance` keeps the current clamp/status transition behavior.

Preserve `completeBossObjective(...)` unchanged.

- [ ] **Step 5: Implement objective router**

`QuestObjectiveEvent.init` validates non-blank target/island, positive amount, and non-blank `sourceQuestId` when non-null.

`QuestObjectiveRouter.apply`:

```kotlin
if (event.type != QuestObjectiveEventType.ENEMY_DEFEATED) return world
var next = world
world.questBoard.active.toSortedMap().forEach { (questId, progress) ->
    if (
        progress.status == QuestStatus.ACTIVE &&
        progress.definition.type == QuestType.HUNT &&
        progress.definition.islandId == event.islandId &&
        progress.definition.targetId == event.targetId &&
        (event.sourceQuestId == null || event.sourceQuestId == questId)
    ) {
        next = QuestEngine.progressObjective(next, questId, event.amount)
    }
}
return next
```

Use exact id equality only; no substring matching.

- [ ] **Step 6: Run GREEN and commit**

Run `bash tools/run-core-tests.sh` and require zero failures.

Commit:

```bash
git add core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt \
        core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt \
        core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt \
        core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: route hunt progress through objective events"
```

---

### Task 2: Deterministic HUNT Combat Factory

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestHuntFactory.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntFactoryTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**

```kotlin
object QuestHuntFactory {
    fun create(world: WorldState, progress: QuestProgress, campaignSeed: Long): CombatState
    fun progressPerVictory(rarity: QuestRarity): Int
    fun encounterIndex(progress: QuestProgress): Int
    fun combatSeed(progress: QuestProgress, campaignSeed: Long): Long
}
```

- [ ] **Step 1: Write failing factory tests**

Register cases for:

```text
same world/progress/seed -> identical CombatState
each rarity -> exact 48/9, 72/12, 100/15, 135/18
p1/p2 current hp/maxHp copied exactly without healing
progress 0 COMMON -> encounter 1
progress 1 COMMON -> encounter 2
progress 2 COMMON -> encounter 3
progress 0/2/4 RARE -> encounters 1/2/3
stable enemy id is <targetId>-hunt-<index>
non-HUNT, non-ACTIVE, wrong island -> reject
encounter index changes combatSeed
```

The current player HP test must use wounded fighters so accidental healing is observable.

- [ ] **Step 2: Run RED**

`bash tools/run-core-tests.sh`

Expected: missing `QuestHuntFactory`.

- [ ] **Step 3: Implement rarity math and deterministic factory**

Use:

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

Stats:

```kotlin
private fun stats(rarity: QuestRarity): Pair<Int, Int> = when (rarity) {
    QuestRarity.COMMON -> 48 to 9
    QuestRarity.RARE -> 72 to 12
    QuestRarity.EPIC -> 100 to 15
    QuestRarity.LEGENDARY -> 135 to 18
}
```

Stable seed must include all spec inputs, for example:

```kotlin
fun combatSeed(progress: QuestProgress, campaignSeed: Long): Long {
    val q = progress.definition
    val encounter = encounterIndex(progress)
    return campaignSeed xor
        (q.questId.hashCode().toLong() * 6364136223846793005L) xor
        (q.targetId.hashCode().toLong() * -7046029254386353131L) xor
        (q.rarity.ordinal.toLong() shl 41) xor
        (encounter.toLong() * 104729L)
}
```

Create `CombatState(round=1, ...)` using current player HP/maxHP and an enemy id `${targetId}-hunt-$encounter`. Derive telegraph target/type from `Random(combatSeed(...))` exactly as existing PvE factories do.

- [ ] **Step 4: Run GREEN and commit**

Run full core suite. Commit:

```bash
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

**Consumes:** `QuestHuntFactory`, `QuestObjectiveRouter`, `QuestEngine`, `CombatEngine`, `CombatModifierResolver`, `HostReplica`, `SnapshotStore`, `DurableCampaignStore`, `StormglassPersistenceAdapter`, `ArcPhase`, `ScenarioStage`.

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

- [ ] **Step 1: Write failing coordinator lifecycle tests**

Create helper `hubWorld(...)` with:

```text
campaign.mode HOST_COOP or SOLO as needed
both completed profiles
positive wounded HP
restored scenario COMPLETE (or activeArc COMPLETE)
active HUNT in questBoard
known berries/bounties/inventory/energy
```

Tests must prove:

```text
valid hub START_HUNT -> activeCombat + quest.hunt.active + no heal
non-HUNT / wrong island / READY status -> reject
missing profile / zero HP -> reject
active combat / legacy scenario combat / voyage / duel -> reject
non-complete arc -> reject
scenario non-complete with no completed arc -> reject
existing hunt or boss binding -> reject
```

- [ ] **Step 2: Add failing action/outcome/idempotency tests**

Cover:

```text
first action locks, second resolves
command retry returns original event and does not mutate twice
equipment attack/damage reduction affects HUNT combat
prepared power world commits energy/mastery exactly once
victory clears combat + binding and advances exact rarity amount only on bound quest
victory does not change berries, bounty, inventory reward flags, or grant turn-in reward
first/second generated victory remains ACTIVE; third becomes READY_TO_TURN_IN
defeat clears binding, keeps terminal CombatStatus.DEFEAT, fails quest permanently, no reward
invalid binding (missing/non-HUNT/inactive quest) rejects
```

For the three-victory case, start from a deterministic low-HP test enemy or repeatedly submit actions until VICTORY; never mutate production combat state directly to fake a victory unless the test is specifically testing the post-resolution helper.

- [ ] **Step 3: Run RED**

`bash tools/run-core-tests.sh`

Expected: missing `QuestHuntCoordinator`.

- [ ] **Step 4: Implement start with full hub authorization**

Before factory creation require:

```kotlin
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

Then require active HUNT/current island, create combat, and commit:

```kotlin
world.copy(
    activeCombat = QuestHuntFactory.create(world, progress, campaignSeed),
    worldFlags = world.worldFlags + (ACTIVE_QUEST_FLAG to questId),
)
```

Start fingerprint:

```text
quest-hunt-start|<playerId>|<questId>
```

Metadata includes `meta.questAction=START_HUNT`, quest id, encounter index, target.

- [ ] **Step 5: Implement action resolution**

Use the same idempotent `existing(...)`, `commit(...)`, and persistence pattern as `QuestBossCoordinator`.

Basic fingerprint:

```text
quest-hunt-combat|<playerId>|<actionType>
```

Resolve with:

```kotlin
CombatEngine(
    QuestHuntFactory.combatSeed(progress, campaignSeed),
    CombatModifierResolver.forWorld(sourceWorld),
)
```

On unresolved lock, persist `activeCombat=locked` and metadata.

On resolved round, synchronize combat fighter HP back to `WorldState.players`.

On ACTIVE, persist the next combat state.

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
        type = QuestObjectiveEventType.ENEMY_DEFEATED,
        targetId = progress.definition.targetId,
        islandId = progress.definition.islandId,
        amount = amount,
        sourceQuestId = questId,
    ),
)
```

Set objective metadata including resulting progress.

On DEFEAT:

```kotlin
val defeated = sourceWorld.copy(
    players = syncedPlayers,
    activeCombat = result.state,
    worldFlags = sourceWorld.worldFlags - ACTIVE_QUEST_FLAG,
)
val next = QuestEngine.fail(defeated, questId, "hunt defeat")
```

Do not heal or reward.

- [ ] **Step 6: Implement prepared-power path**

Mirror `QuestBossCoordinator.submitPreparedAction`: verify campaign id, use `sourceFingerprint` from original `PowerAction`, and resolve against `preparedWorld` so energy/mastery state is in the same committed event as the combat action.

- [ ] **Step 7: Run GREEN and commit**

Run full core suite. Commit:

```bash
git add core/src/main/kotlin/grandlineduo/game/quest/QuestHuntCoordinator.kt \
        core/src/test/kotlin/grandlineduo/game/quest/QuestHuntCoordinatorTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: add authoritative hunt combat lifecycle"
```

---

### Task 4: Handler Routing and Invalid Source Exclusivity

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Create or extend: `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntCoordinatorTest.kt` with handler cases, or create `QuestHuntRoutingTest.kt` if the file becomes unwieldy.
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt` only if a new test object is created.

**Routing order:**
- QuestAction `START_HUNT` -> HUNT coordinator.
- Structured CombatAction: reject HUNT+BOSS double binding; HUNT before BOSS before arc.
- PowerAction: preserve duel priority; then reject HUNT+BOSS; HUNT before BOSS before arc/scenario.

- [ ] **Step 1: Write failing handler tests**

Use `StormglassGameplayCommandHandler` directly and assert:

```text
START_HUNT starts bound HUNT combat
manual PROGRESS on HUNT rejects without mutation
HUNT-bound basic CombatAction routes to hunt coordinator
HUNT-bound prepared Haki/Akuma action routes atomically and spends energy once
simultaneous quest.hunt.active + quest.boss.active rejects before any combat route
existing START_BOSS behavior still starts boss and not hunt
ordinary arc/scenario combat without hunt binding still follows existing route
```

- [ ] **Step 2: Run RED**

`bash tools/run-core-tests.sh`

Expected: START_HUNT unknown and HUNT-bound combat falls through to existing routes.

- [ ] **Step 3: Instantiate and route QuestHuntCoordinator**

Add one coordinator beside existing boss/duel coordinators:

```kotlin
private val questHuntCoordinator = QuestHuntCoordinator(
    hostReplica = hostReplica,
    campaignSeed = seed,
    snapshotStore = snapshotStore,
    durableStore = durableStore,
)
```

QuestAction early route:

```kotlin
if (command.actionType.equals("START_HUNT", ignoreCase = true)) {
    return questHuntCoordinator.start(command.commandId, command.actorId, command.questId, hostTimestamp)
}
```

- [ ] **Step 4: Route basic combat by explicit bindings**

Before current quest-boss branch:

```kotlin
val huntBound = before.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG] != null
val bossBound = before.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] != null
require(!(huntBound && bossBound)) { "Invalid simultaneous HUNT and BOSS combat bindings" }
return when {
    huntBound -> questHuntCoordinator.submitAction(...)
    bossBound -> questBossCoordinator.submitAction(...)
    else -> arcCombatCoordinator.submitAction(...)
}
```

Keep legacy scenario combat handling untouched.

- [ ] **Step 5: Route powers after duel priority**

Inside `applyPowerAction`, after `PowerTechniqueEngine.prepare` and existing active-duel branch, compute HUNT/BOSS binding booleans on `poweredWorld`, reject both, then route HUNT prepared action before existing BOSS path.

Do not change power preparation formulas.

- [ ] **Step 6: Run GREEN and commit**

Run full core suite. Commit:

```bash
git add core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt \
        core/src/test/kotlin/grandlineduo/game/quest/QuestHuntCoordinatorTest.kt \
        core/src/test/kotlin/grandlineduo/game/quest/QuestHuntRoutingTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: route hunt combat through host authority"
```

Only add files that actually exist.

---

### Task 5: Presenter and SOLO Session Flow

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`
- Modify production `GameSessionCoordinator.kt` only if the RED proves generic QUEST + existing combat autoplay is insufficient.

- [ ] **Step 1: Write failing presenter tests**

Create active quest-board worlds and assert:

```text
ACTIVE HUNT progress 0 -> action START_HUNT|id|1 labeled "Rastrear e enfrentar alvo"
ACTIVE HUNT progress >0 -> START_HUNT labeled "Continuar caçada"
ACTIVE HUNT never exposes PROGRESS
READY HUNT -> TURN_IN only
BOSS -> START_BOSS behavior unchanged
EXPLORE/COLLECT/RESCUE/ESCORT/INVESTIGATE -> PROGRESS retained
```

- [ ] **Step 2: Write SOLO session integration test**

Using public `GameSessionCoordinator` APIs:

1. start SOLO and create P1 (which creates P2 companion);
2. reach/create a hub-compatible state using the existing test helper pattern;
3. seed/accept a HUNT through authoritative quest commands/helper state setup used by existing quest session tests;
4. call `submitQuestAction("START_HUNT", questId)`;
5. call P1 `submitCombatAction(...)`;
6. assert existing `autoPlayCompanion()` supplies P2 action and the round resolves/advances without adding a new AI path;
7. assert defeat remains possible in a deliberately lethal fixture or at minimum that no healing/reward shortcut occurs.

This test should initially fail only on the missing HUNT feature, not because the test bypasses public gameplay semantics.

- [ ] **Step 3: Run RED**

`bash tools/run-core-tests.sh`

Expected: presenter still exposes manual HUNT progress and START_HUNT flow is absent.

- [ ] **Step 4: Implement presenter migration**

Replace the ACTIVE non-BOSS branch with explicit type routing:

```kotlin
when (progress.definition.type) {
    QuestType.BOSS -> if (world.activeCombat == null) add(GameAction("START_BOSS|${id}|1", "Enfrentar alvo • ${title}", "QUEST"))
    QuestType.HUNT -> if (world.activeCombat == null) {
        val label = if (progress.progress == 0) "Rastrear e enfrentar alvo" else "Continuar caçada"
        add(GameAction("START_HUNT|${id}|1", "$label • $title", "QUEST"))
    }
    else -> add(GameAction("PROGRESS|${id}|1", "Registrar progresso • $title", "QUEST"))
}
```

Do not add a new `GameScreen` or Android dispatcher kind.

- [ ] **Step 5: Verify existing SOLO planner is sufficient**

Run the session test. Because `submitCombatAction` and `submitPowerAction` already call `autoPlayCompanion()`, production session code should need no change. If it fails, use systematic-debugging and change the minimum real deficiency; do not create HUNT-specific AI.

- [ ] **Step 6: Run GREEN and commit**

Run full core suite. Commit presenter/tests and only any proven session production change:

```bash
git add core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt \
        core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt \
        core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt
git commit -m "feat: expose real hunt encounters in quest ui"
```

---

### Task 6: Real TCP Reconnect, Locked Round, and Objective Convergence

**Files:**
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestHuntLanIntegrationTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

- [ ] **Step 1: Write and register one real TCP lifecycle test**

Follow the existing `QuestLanIntegrationTest` / duel LAN socket pattern. Execute exactly:

1. Build HOST_COOP hub world with two complete wounded characters, known HP/energy/berries/bounties/inventory flags, and one ACTIVE HUNT.
2. Start `LanHostServer` + `StormglassGameplayCommandHandler`.
3. Connect P2 `ClientReplica` over real loopback TCP.
4. P2 sends `QuestAction(START_HUNT)`; refresh; assert host/client bound combat convergence.
5. P1 locks a combat action; refresh P2 and record its confirmed state/hash.
6. Disconnect P2 before P2 resolves the round.
7. Recreate a fresh P2 replica from its last confirmed stale state and reconnect.
8. Refresh; assert the same HUNT binding, encounter enemy id, round, locked P1 action, player HP, and canonical hash converge.
9. P2 resolves; continue deterministic rounds until VICTORY.
10. Assert combat + binding clear and only the bound HUNT progressed by the exact rarity amount.
11. Assert berries, bounties, inventory/reward flags, and unrelated quest progress are unchanged.
12. Repeat start/reconnect/resolution as needed until the third victory makes the HUNT `READY_TO_TURN_IN`.
13. `TURN_IN` once and assert existing reward behavior occurs exactly once.
14. Resend the exact same TURN_IN command id and assert idempotent no duplicate reward.

If running three full TCP battles makes the test excessively slow, keep one reconnect in encounter 1 and complete encounters 2/3 over the same live TCP session; all three victories must still be authoritative wire commands.

- [ ] **Step 2: Run RED/GREEN appropriately**

If the test exposes a production reconnect/source-binding bug, invoke systematic-debugging, add the smallest focused regression assertion, implement the root-cause fix, and rerun.

Run:

```bash
bash tools/run-core-tests.sh
```

Require the new real-TCP test plus all previous tests to pass.

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/grandlineduo/game/quest/QuestHuntLanIntegrationTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "test: cover hunt quest reconnect over real tcp"
```

Include production files in this commit only if a verified reconnect bug required them.

---

### Task 7: Full Regression, Android Build, and PR Evidence

**Files:**
- Modify: `docs/superpowers/plans/2026-08-22-quest-objective-events-hunt.md` only to record actual verification output.
- Update PR #4 description with concrete HUNT evidence.
- Temporary workflow may be created only if local Android toolchain/network remains unavailable; it must be removed after successful verification.

- [ ] **Step 1: Read verification-before-completion skill and run exact-head core suite**

Run:

```bash
bash tools/run-core-tests.sh
```

Require `RESULT N/N passed`, zero failures. Explicitly inspect pass lines for:

```text
manual HUNT progress rejection
objective router bound-source behavior
factory rarity/no-heal/determinism
coordinator hub validation, three encounters, defeat, power/idempotency
START_HUNT handler routing
existing START_BOSS regression
presenter migration + SOLO planner
real TCP HUNT reconnect/convergence/exactly-once turn-in
existing quest boss, duel, arc boss, powers, persistence and LAN suites
```

- [ ] **Step 2: Verify compatibility invariants**

Confirm by diff/test evidence:

```text
CombatEngine.kt unchanged
QuestBossFactory/QuestBossCoordinator behavior unchanged unless an explicit test-only compatibility assertion was added
Protocol.PROTOCOL_VERSION remains 5
QuestAction remains subtype 9
WorldStateCodec CURRENT_VERSION remains 11
no new canonical encoding block or snapshot field
EXPLORE/COLLECT/RESCUE/ESCORT/INVESTIGATE manual PROGRESS still works
HUNT victory never pays reward before TURN_IN
```

- [ ] **Step 3: Build Android from the same source tree**

Run:

```bash
gradle --no-daemon --stacktrace :app:assembleDebug
test -s app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

If local Gradle cannot run because the environment cannot access the repository/toolchain, add a temporary PR workflow that runs the full core suite then `:app:assembleDebug`, verifies a non-empty APK, prints SHA-256, and checks out the exact PR merge source. Remove that workflow afterward and compare the build-tested source head to the final clean head to prove only the workflow removal/docs changed.

- [ ] **Step 4: Record evidence**

Append to this plan only actual values observed:

```text
final clean feature head
PR merge SHA tested
Core CI run/job and exact N/N
Android verification run/job
BUILD SUCCESSFUL output
APK SHA-256
post-build diff proof if a temporary workflow was removed
```

No guessed counts/hashes.

- [ ] **Step 5: Update PR #4 without merging**

Add a HUNT section explaining objective events, three real encounters, hardcore/no-heal semantics, Haki/Akuma/equipment reuse, SOLO reuse, TCP reconnect, compatibility, exact test count and APK hash.

Keep PR open and do not merge without explicit user integration choice.

- [ ] **Step 6: Final documentation commit and exact-head CI**

If verification notes changed the plan, commit them:

```bash
git add docs/superpowers/plans/2026-08-22-quest-objective-events-hunt.md
git commit -m "docs: record hunt quest verification"
```

Run/check Core CI again on that final documentation head before claiming completion.
