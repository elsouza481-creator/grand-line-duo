# EXPLORE + COLLECT Field Objectives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert EXPLORE and COLLECT contracts from manual progress into deterministic host-authoritative D20 field actions with 1 PE cost, visible roll state, exact objective routing, persistence, idempotency and real TCP reconnect coverage.

**Architecture:** Extend the existing `QuestObjectiveRouter` to authorize EXPLORE/COLLECT objective events, add a pure `QuestFieldResolver` for D20 rules, and add a `QuestFieldCoordinator` for atomic energy/state/persistence. Keep `GameplayWireCommand.QuestAction` subtype 9, protocol v5 and snapshot v11 unchanged; handler and presenter only gain new action strings.

**Tech Stack:** Kotlin/JVM core module, custom Kotlin test runner, existing `HostReplica`/`ReplaceWorldStateCommand`, snapshot/event persistence, loopback TCP LAN tests, Android Gradle build.

**Spec:** `docs/superpowers/specs/2026-08-24-explore-collect-field-objectives-design.md`

## Global Constraints

- `WorldStateCodec.CURRENT_VERSION` remains **11**; no structured snapshot field is added.
- `PROTOCOL_VERSION` remains **5**.
- `GameplayWireCommand.QuestAction` remains wire subtype **9**; `DuelAction` remains subtype **10**.
- `CombatEngine.kt`, `QuestBossFactory.kt` and `QuestBossCoordinator.kt` must remain unchanged.
- HUNT behavior remains unchanged and green.
- Field state lives only in existing `worldFlags` plus existing quest progress/player energy.
- Every accepted EXPLORE/COLLECT field attempt spends exactly **1 PE**, success or failure.
- Rewards remain exclusively behind existing `TURN_IN`.
- RESCUE/ESCORT/INVESTIGATE retain manual `PROGRESS` in this slice.
- Shop buys, inventory grants/rewards, campaign arrival, voyage completion and ordinary story/arc choices do not progress EXPLORE/COLLECT.
- New production code is written only after its corresponding RED test is observed.

---

## File Structure

**Create**
- `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldResolver.kt` — pure field-action type, result model, D20/CD/modifier/seed/progress rules.
- `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldState.kt` — exact world-flag key/read/write/cleanup helpers for attempt + visible last roll.
- `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldCoordinator.kt` — host authority, validation, PE spend, objective application, metadata, idempotency, persistence.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldResolverTest.kt` — deterministic pure rules.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldCoordinatorTest.kt` — atomic lifecycle, invalid states, cleanup/idempotency.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldRoutingTest.kt` — handler routing + negative integration boundaries.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldLanIntegrationTest.kt` — real TCP reconnect/convergence/reward lifecycle.

**Modify**
- `core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt` — add `LOCATION_VISITED`; route exact EXPLORE/COLLECT matches.
- `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt` — manual/objective authorization and field-state cleanup on `TURN_IN`/`FAIL`.
- `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt` — own/route `QuestFieldCoordinator`, require `amount == 1`.
- `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt` — EXPLORE/COLLECT actions, zero-PE hint, last-roll line.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt` — authorization/cleanup expectations.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt` — EXPLORE/COLLECT matching and reserved no-op events.
- `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt` — labels, zero PE, last roll, remaining migration types.
- `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt` — SOLO single-actor field semantics.
- `core/src/test/kotlin/grandlineduo/test/TestRunner.kt` — register each new test object.
- `docs/superpowers/plans/2026-08-24-explore-collect-field-objectives.md` — append observed verification evidence after completion.
- PR #4 title/body — update only after final evidence exists.

---

### Task 1: Authorize EXPLORE/COLLECT Objective Progress and Router Events

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt`

**Interfaces:**
- Consumes: existing `QuestEngine.progress(...)`, `QuestEngine.progressObjective(...)`, `QuestObjectiveEvent`.
- Produces: `QuestObjectiveEventType.LOCATION_VISITED`; `progressObjective` accepts ACTIVE HUNT/EXPLORE/COLLECT; manual progress supports only RESCUE/ESCORT/INVESTIGATE.

- [ ] **Step 1: Replace the migration authorization tests with failing EXPLORE/COLLECT automation expectations**

In `QuestEngineTest.register()`, replace the old “migration types” expectations with:

```kotlin
test("manual quest progress rejects automated types while remaining migration types still work") {
    listOf(QuestType.HUNT, QuestType.BOSS, QuestType.EXPLORE, QuestType.COLLECT).forEach { type ->
        val quest = sampleQuest().copy(
            questId = "manual-reject-${type.name.lowercase()}",
            type = type,
            targetId = "target-${type.name.lowercase()}",
            requiredAmount = if (type == QuestType.BOSS) 1 else 3,
        )
        val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")
        assertTrue(runCatching { QuestEngine.progress(accepted, quest.questId, 1) }.isFailure)
        assertEquals(0, accepted.questBoard.active.getValue(quest.questId).progress)
    }

    listOf(QuestType.RESCUE, QuestType.ESCORT, QuestType.INVESTIGATE).forEach { type ->
        val quest = sampleQuest().copy(
            questId = "manual-keep-${type.name.lowercase()}",
            type = type,
            targetId = "target-${type.name.lowercase()}",
        )
        val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")
        assertEquals(1, QuestEngine.progress(accepted, quest.questId, 1)
            .questBoard.active.getValue(quest.questId).progress)
    }
}

test("objective progress accepts active hunt explore and collect only") {
    listOf(QuestType.HUNT, QuestType.EXPLORE, QuestType.COLLECT).forEach { type ->
        val quest = sampleQuest().copy(
            questId = "objective-${type.name.lowercase()}",
            type = type,
            targetId = "objective-${type.name.lowercase()}",
            requiredAmount = 3,
        )
        val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")
        val advanced = QuestEngine.progressObjective(accepted, quest.questId, 1)
        assertEquals(1, advanced.questBoard.active.getValue(quest.questId).progress)
    }

    listOf(QuestType.BOSS, QuestType.RESCUE, QuestType.ESCORT, QuestType.INVESTIGATE).forEach { type ->
        val quest = sampleQuest().copy(
            questId = "objective-reject-${type.name.lowercase()}",
            type = type,
            targetId = "objective-${type.name.lowercase()}",
            requiredAmount = if (type == QuestType.BOSS) 1 else 3,
        )
        val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")
        assertTrue(runCatching { QuestEngine.progressObjective(accepted, quest.questId, 1) }.isFailure)
    }
}
```

- [ ] **Step 2: Add failing router tests for EXPLORE/COLLECT exact matching**

Add helpers that can create different quest types/targets and tests:

```kotlin
test("location visited advances only the exact bound explore contract") {
    val exploreA = objective("explore-a", QuestType.EXPLORE, "forgotten-ruins", required = 3)
    val exploreB = objective("explore-b", QuestType.EXPLORE, "forgotten-ruins", required = 3)
    val world = worldWith(exploreA, exploreB)

    val next = QuestObjectiveRouter.apply(
        world,
        QuestObjectiveEvent(
            QuestObjectiveEventType.LOCATION_VISITED,
            "forgotten-ruins",
            "shells-town",
            1,
            "explore-a",
        ),
    )

    assertEquals(1, next.questBoard.active.getValue("explore-a").progress)
    assertEquals(0, next.questBoard.active.getValue("explore-b").progress)
}

test("item acquired advances only the exact bound collect contract") {
    val collectA = objective("collect-a", QuestType.COLLECT, "medical-supplies", required = 4)
    val collectB = objective("collect-b", QuestType.COLLECT, "medical-supplies", required = 4)
    val world = worldWith(collectA, collectB)

    val next = QuestObjectiveRouter.apply(
        world,
        QuestObjectiveEvent(
            QuestObjectiveEventType.ITEM_ACQUIRED,
            "medical-supplies",
            "shells-town",
            1,
            "collect-a",
        ),
    )

    assertEquals(1, next.questBoard.active.getValue("collect-a").progress)
    assertEquals(0, next.questBoard.active.getValue("collect-b").progress)
}

test("reserved and future objective event types remain no op") {
    val world = worldWith(objective("explore-a", QuestType.EXPLORE, "forgotten-ruins", 3))
    listOf(
        QuestObjectiveEventType.ISLAND_VISITED,
        QuestObjectiveEventType.NPC_RESCUED,
        QuestObjectiveEventType.ESCORT_ARRIVED,
        QuestObjectiveEventType.CLUE_DISCOVERED,
    ).forEach { type ->
        assertEquals(
            world,
            QuestObjectiveRouter.apply(world, QuestObjectiveEvent(type, "forgotten-ruins", "shells-town", 1, "explore-a")),
        )
    }
}
```

Also cover wrong source, wrong target, wrong island, wrong event/quest type and READY state as no-ops.

- [ ] **Step 3: Run the core suite and confirm RED is caused by the new authorization/event requirements**

Run:

```bash
bash tools/run-core-tests.sh
```

Expected: failures because `LOCATION_VISITED` does not exist and EXPLORE/COLLECT authorization is still old. Do not modify production until this RED is observed.

- [ ] **Step 4: Implement minimal QuestEngine authorization**

Change the manual guard to:

```kotlin
require(current.definition.type in setOf(QuestType.RESCUE, QuestType.ESCORT, QuestType.INVESTIGATE)) {
    "${current.definition.type.name} contracts progress only through authoritative objectives"
}
```

Change objective authorization to:

```kotlin
require(current.definition.type in setOf(QuestType.HUNT, QuestType.EXPLORE, QuestType.COLLECT)) {
    "Objective progress is not enabled for ${current.definition.type.name}"
}
```

Keep ACTIVE-only validation and the shared `advance(...)` clamp unchanged.

- [ ] **Step 5: Implement minimal typed router mapping**

Add `LOCATION_VISITED` to the enum and replace the ENEMY-only early return with an exact event-to-quest mapping:

```kotlin
val questType = when (event.type) {
    QuestObjectiveEventType.ENEMY_DEFEATED -> QuestType.HUNT
    QuestObjectiveEventType.LOCATION_VISITED -> QuestType.EXPLORE
    QuestObjectiveEventType.ITEM_ACQUIRED -> QuestType.COLLECT
    QuestObjectiveEventType.ISLAND_VISITED,
    QuestObjectiveEventType.NPC_RESCUED,
    QuestObjectiveEventType.ESCORT_ARRIVED,
    QuestObjectiveEventType.CLUE_DISCOVERED -> return world
}
```

Then retain sorted quest iteration and exact status/island/target/source matching, replacing the hard-coded HUNT type with `questType`.

- [ ] **Step 6: Run the full core suite for GREEN**

Run:

```bash
bash tools/run-core-tests.sh
```

Expected: all existing tests plus new Task 1 tests pass.

- [ ] **Step 7: Commit Task 1**

```bash
git add core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt \
  core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt
git commit -m "feat: authorize explore and collect objective events"
```

---

### Task 2: Add the Pure Deterministic QuestFieldResolver

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldResolver.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldResolverTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: `WorldState`, `QuestProgress`, `QuestRarity`, `QuestObjectiveEventType`, `Attribute`, `Skill`.
- Produces:

```kotlin
enum class QuestFieldActionType { EXPLORE_SITE, SEARCH_SUPPLIES }

data class QuestFieldAttemptResult(
    val actionType: QuestFieldActionType,
    val questId: String,
    val targetId: String,
    val islandId: String,
    val actorId: String,
    val attemptOrdinal: Int,
    val checkId: String,
    val roll: Int,
    val modifier: Int,
    val total: Int,
    val difficultyClass: Int,
    val success: Boolean,
    val objectiveEventType: QuestObjectiveEventType,
    val progressAmount: Int,
)
```

and `QuestFieldResolver.resolve(...)`, `rollSeed(...)`, `difficultyClass(...)`, `progressPerSuccess(...)`.

- [ ] **Step 1: Write resolver RED tests and register them**

Create `QuestFieldResolverTest.kt` with a profile helper containing all seven attributes and specific skills. Register `grandlineduo.game.quest.QuestFieldResolverTest.register()` in `TestRunner.kt` immediately after `QuestObjectiveRouterTest`.

Required exact tests:

```kotlin
test("field rarity maps to exact dc and progress multiplier") {
    assertEquals(10, QuestFieldResolver.difficultyClass(QuestRarity.COMMON))
    assertEquals(12, QuestFieldResolver.difficultyClass(QuestRarity.RARE))
    assertEquals(15, QuestFieldResolver.difficultyClass(QuestRarity.EPIC))
    assertEquals(18, QuestFieldResolver.difficultyClass(QuestRarity.LEGENDARY))
    assertEquals(1, QuestFieldResolver.progressPerSuccess(QuestRarity.COMMON))
    assertEquals(2, QuestFieldResolver.progressPerSuccess(QuestRarity.RARE))
    assertEquals(3, QuestFieldResolver.progressPerSuccess(QuestRarity.EPIC))
    assertEquals(4, QuestFieldResolver.progressPerSuccess(QuestRarity.LEGENDARY))
}
```

```kotlin
test("explore uses perception plus best field skill with deterministic tie priority") {
    val world = fieldWorld(
        per = 3,
        int = 1,
        skills = mapOf(Skill.PERCEPTION to 2, Skill.SURVIVAL to 2, Skill.INVESTIGATION to 2),
    )
    val result = QuestFieldResolver.resolve(
        world, activeQuest(QuestType.EXPLORE), "p1", QuestFieldActionType.EXPLORE_SITE, 1, 11L,
    )
    assertEquals("PER + PERCEPTION", result.checkId)
    assertEquals(5, result.modifier)
}
```

```kotlin
test("collect chooses numerically best check then fixed tie priority") {
    val world = fieldWorld(
        per = 2,
        int = 3,
        skills = mapOf(Skill.SURVIVAL to 2, Skill.MEDICINE to 1, Skill.INVESTIGATION to 1),
    )
    val result = QuestFieldResolver.resolve(
        world, activeQuest(QuestType.COLLECT), "p1", QuestFieldActionType.SEARCH_SUPPLIES, 1, 12L,
    )
    assertEquals("PER + SURVIVAL", result.checkId)
    assertEquals(4, result.modifier)
}
```

Add deterministic identity tests that compare `rollSeed(...)`, not d20 inequality:

```kotlin
test("every authoritative identity input participates in field roll seed") {
    val base = activeQuest(QuestType.EXPLORE)
    val seed = QuestFieldResolver.rollSeed(base, "p1", QuestFieldActionType.EXPLORE_SITE, 1, 99L)
    assertTrue(seed != QuestFieldResolver.rollSeed(base.copy(definition = base.definition.copy(questId = "other")), "p1", QuestFieldActionType.EXPLORE_SITE, 1, 99L))
    assertTrue(seed != QuestFieldResolver.rollSeed(base.copy(definition = base.definition.copy(targetId = "other-target")), "p1", QuestFieldActionType.EXPLORE_SITE, 1, 99L))
    assertTrue(seed != QuestFieldResolver.rollSeed(base.copy(definition = base.definition.copy(rarity = QuestRarity.RARE)), "p1", QuestFieldActionType.EXPLORE_SITE, 1, 99L))
    assertTrue(seed != QuestFieldResolver.rollSeed(base, "p2", QuestFieldActionType.EXPLORE_SITE, 1, 99L))
    assertTrue(seed != QuestFieldResolver.rollSeed(base.copy(definition = base.definition.copy(type = QuestType.COLLECT)), "p1", QuestFieldActionType.SEARCH_SUPPLIES, 1, 99L))
    assertTrue(seed != QuestFieldResolver.rollSeed(base, "p1", QuestFieldActionType.EXPLORE_SITE, 2, 99L))
    assertTrue(seed != QuestFieldResolver.rollSeed(base, "p1", QuestFieldActionType.EXPLORE_SITE, 1, 100L))
}
```

Also assert identical inputs give identical result, `roll in 1..20`, `total == roll + modifier`, `success == (total >= difficultyClass)`, and mismatched action/type fails.

- [ ] **Step 2: Run the core suite to observe RED**

```bash
bash tools/run-core-tests.sh
```

Expected: unresolved `QuestFieldResolver`, `QuestFieldActionType` and `QuestFieldAttemptResult` symbols.

- [ ] **Step 3: Implement the pure resolver**

Create `QuestFieldResolver.kt`. Use exact rule tables:

```kotlin
enum class QuestFieldActionType { EXPLORE_SITE, SEARCH_SUPPLIES }

object QuestFieldResolver {
    fun difficultyClass(rarity: QuestRarity): Int = when (rarity) {
        QuestRarity.COMMON -> 10
        QuestRarity.RARE -> 12
        QuestRarity.EPIC -> 15
        QuestRarity.LEGENDARY -> 18
    }

    fun progressPerSuccess(rarity: QuestRarity): Int = rarity.ordinal + 1
}
```

EXPLORE check candidates are exactly:

```kotlin
listOf(
    "PER + PERCEPTION" to (per + rank(Skill.PERCEPTION)),
    "PER + SURVIVAL" to (per + rank(Skill.SURVIVAL)),
    "PER + INVESTIGATION" to (per + rank(Skill.INVESTIGATION)),
)
```

COLLECT candidates are exactly:

```kotlin
listOf(
    "PER + SURVIVAL" to (per + rank(Skill.SURVIVAL)),
    "INT + MEDICINE" to (intelligence + rank(Skill.MEDICINE)),
    "INT + INVESTIGATION" to (intelligence + rank(Skill.INVESTIGATION)),
)
```

Select with `maxBy { it.second }`; Kotlin list order resolves ties to the first candidate. Missing skill rank is 0. Read `Attribute.PER`/`Attribute.INT` with `getValue` from the complete profile.

Freeze seed composition with explicit constants:

```kotlin
fun rollSeed(progress: QuestProgress, actorId: String, actionType: QuestFieldActionType, attemptOrdinal: Int, campaignSeed: Long): Long {
    require(attemptOrdinal > 0) { "Field attempt ordinal must be positive" }
    val quest = progress.definition
    return campaignSeed xor
        (quest.questId.hashCode().toLong() * 6364136223846793005L) xor
        (quest.targetId.hashCode().toLong() * -7046029254386353131L) xor
        (quest.rarity.ordinal.toLong() shl 41) xor
        (actionType.ordinal.toLong() shl 33) xor
        (actorId.hashCode().toLong() * 104729L) xor
        (attemptOrdinal.toLong() * 15485863L)
}
```

Use `Random(rollSeed(...)).nextInt(20) + 1`; natural 1/20 have no special rule.

- [ ] **Step 4: Run full core suite for GREEN**

```bash
bash tools/run-core-tests.sh
```

Expected: all tests pass including resolver determinism.

- [ ] **Step 5: Commit Task 2**

```bash
git add core/src/main/kotlin/grandlineduo/game/quest/QuestFieldResolver.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestFieldResolverTest.kt \
  core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: add deterministic quest field resolver"
```

---

### Task 3: Add Atomic QuestFieldCoordinator and Exact Field-State Cleanup

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldState.kt`
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldCoordinator.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldCoordinatorTest.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: Task 1 objective routing, Task 2 resolver.
- Produces: `QuestFieldState` exact flag helper and `QuestFieldCoordinator.attempt(...)`.

- [ ] **Step 1: Write failing exact-key field-state and coordinator tests**

Register `QuestFieldCoordinatorTest.register()` in `TestRunner.kt`.

Create `QuestFieldCoordinatorTest.kt` with a hub fixture using `ScenarioStage.COMPLETE` or `ArcPhase.COMPLETE`, complete P1/P2 profiles, positive HP, known energy, active field quests and no combat/voyage/duel.

Required tests include:

```kotlin
test("valid explore attempt spends one energy records ordinal and visible result") {
    val quest = exploreQuest("field-explore")
    val initial = hubWorld(quest, p1Energy = 7)
    val host = HostReplica(initial)
    val coordinator = QuestFieldCoordinator(host, campaignSeed = 501L)

    val event = coordinator.attempt("field-explore-cmd", "p1", quest.questId, QuestFieldActionType.EXPLORE_SITE, 1_000)

    assertEquals(6, host.state.players.getValue("p1").energy)
    assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
    val last = QuestFieldState.readLast(host.state, quest.questId)!!
    assertEquals("p1", last.actorId)
    assertEquals(QuestFieldActionType.EXPLORE_SITE, last.actionType)
    assertEquals("1", event.payload["meta.questFieldEnergySpent"])
}
```

For failure/success without depending on luck, select a deterministic campaign seed in the fixture by scanning a small bounded range in test setup using `QuestFieldResolver.resolve(...)` before constructing the coordinator. Use a helper like:

```kotlin
private fun seedFor(world: WorldState, progress: QuestProgress, actorId: String, action: QuestFieldActionType, wantSuccess: Boolean): Long =
    (1L..10_000L).first { seed ->
        QuestFieldResolver.resolve(world, progress, actorId, action, 1, seed).success == wantSuccess
    }
```

Then assert failure spends PE and leaves progress 0; success emits the bound event and exact progress amount; same-target sibling remains 0; final success clamps and makes READY.

Add idempotency:

```kotlin
val first = coordinator.attempt("same-cmd", "p2", quest.questId, QuestFieldActionType.SEARCH_SUPPLIES, 2_000)
val after = host.state
val retry = coordinator.attempt("same-cmd", "p2", quest.questId, QuestFieldActionType.SEARCH_SUPPLIES, 2_001)
assertEquals(first.eventId, retry.eventId)
assertEquals(after, host.state)
```

Add collision with same command id but different quest/action and assert failure/no mutation.

Add a table of invalid worlds: profile missing, either HP 0, actor energy 0, wrong island, READY quest, mismatched action, active structured combat, restored legacy combat, active voyage, active duel, HUNT binding, BOSS binding, incomplete arc, incomplete scenario. Every case must leave `host.state == invalid`.

- [ ] **Step 2: Add failing cleanup tests to QuestEngineTest**

Seed exact field flags for two quests, resolve only one, and prove exact-key cleanup:

```kotlin
val withFlags = QuestFieldState.writeAttemptResult(world, questA.questId, resultA)
val withBoth = QuestFieldState.writeAttemptResult(withFlags, questB.questId, resultB)
val turnedIn = QuestEngine.turnIn(readyWorld(withBoth, questA.questId), questA.questId)
assertEquals(null, QuestFieldState.readLast(turnedIn, questA.questId))
assertEquals(0, QuestFieldState.attemptCount(turnedIn, questA.questId))
assertTrue(QuestFieldState.readLast(turnedIn, questB.questId) != null)
```

Repeat cleanup through `QuestEngine.fail(...)`. HUNT/BOSS with no field flags must remain behaviorally unchanged.

- [ ] **Step 3: Run core suite and observe RED**

```bash
bash tools/run-core-tests.sh
```

Expected: missing `QuestFieldState`/`QuestFieldCoordinator` symbols.

- [ ] **Step 4: Implement QuestFieldState with exact keys only**

Create:

```kotlin
data class QuestFieldLastResult(
    val actorId: String,
    val actionType: QuestFieldActionType,
    val checkId: String,
    val roll: Int,
    val modifier: Int,
    val total: Int,
    val difficultyClass: Int,
    val success: Boolean,
)
```

Use functions:

```kotlin
object QuestFieldState {
    fun attemptCount(world: WorldState, questId: String): Int
    fun readLast(world: WorldState, questId: String): QuestFieldLastResult?
    fun writeAttemptResult(world: WorldState, questId: String, result: QuestFieldAttemptResult): WorldState
    fun clear(world: WorldState, questId: String): WorldState
}
```

`clear(...)` must remove exactly these nine keys constructed with the supplied quest id: attempt, last actor/action/check/roll/modifier/total/dc/success. Never scan with `endsWith(questId)`.

- [ ] **Step 5: Implement QuestEngine cleanup**

After normal transition/reward work in `turnIn`, return `QuestFieldState.clear(rewarded, questId)`. In `fail`, build the failed world then return `QuestFieldState.clear(failed, questId)`.

- [ ] **Step 6: Implement QuestFieldCoordinator atomic attempt**

Follow the existing coordinator pattern (`existing`, `commit`, `persist`) from `QuestHuntCoordinator`, with fingerprint:

```text
quest-field|<playerId>|<actionType>|<questId>
```

Validation order before mutation:

```kotlin
require(playerId == "p1" || playerId == "p2")
val world = hostReplica.state
val restored = StormglassPersistenceAdapter.decode(world)
require(world.players["p1"]?.profile != null && world.players["p2"]?.profile != null)
require((world.players["p1"]?.hp ?: 0) > 0 && (world.players["p2"]?.hp ?: 0) > 0)
require((world.players[playerId]?.energy ?: 0) >= 1)
require(world.activeCombat == null && restored.combat == null)
require(world.activeVoyage == null)
require(world.activeDuel == null)
require(world.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG] == null)
require(world.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] == null)
require(world.activeArc == null || world.activeArc.phase == ArcPhase.COMPLETE)
if (world.activeArc == null) require(restored.scenario.stage == ScenarioStage.COMPLETE)
```

Validate active quest/id/island/type/action. Either P1/P2 may attempt regardless of `acceptedBy`.

Atomic mutation:

```kotlin
val ordinal = QuestFieldState.attemptCount(world, questId) + 1
val resolved = QuestFieldResolver.resolve(world, progress, playerId, actionType, ordinal, campaignSeed)
val player = world.players.getValue(playerId)
var next = world.copy(players = world.players + (playerId to player.copy(energy = player.energy - 1)))
next = QuestFieldState.writeAttemptResult(next, questId, resolved)
if (resolved.success) {
    next = QuestObjectiveRouter.apply(
        next,
        QuestObjectiveEvent(
            type = resolved.objectiveEventType,
            targetId = resolved.targetId,
            islandId = resolved.islandId,
            amount = resolved.progressAmount,
            sourceQuestId = questId,
        ),
    )
}
```

Commit only once. Metadata must include every field from spec section 18; success adds objective target/amount/new progress. Failure includes no objective metadata.

- [ ] **Step 7: Run full core suite for GREEN**

```bash
bash tools/run-core-tests.sh
```

Expected: coordinator + cleanup tests pass with no HUNT/BOSS regression.

- [ ] **Step 8: Commit Task 3**

```bash
git add core/src/main/kotlin/grandlineduo/game/quest/QuestFieldState.kt \
  core/src/main/kotlin/grandlineduo/game/quest/QuestFieldCoordinator.kt \
  core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestFieldCoordinatorTest.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt \
  core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: add authoritative quest field attempts"
```

---

### Task 4: Route Field Actions Through the Existing Quest Wire Path

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldRoutingTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: `QuestFieldCoordinator.attempt(...)`, existing `GameplayWireCommand.QuestAction`.
- Produces: `EXPLORE_SITE`/`SEARCH_SUPPLIES` handler routes with existing wire subtype 9.

- [ ] **Step 1: Write handler RED tests and register them**

Register `QuestFieldRoutingTest.register()` after HUNT routing.

Create tests:

```kotlin
test("handler routes explore site through existing quest wire action") {
    val quest = fieldQuest("route-explore", QuestType.EXPLORE)
    val initial = hubWorld(quest)
    val host = HostReplica(initial)
    val handler = StormglassGameplayCommandHandler(host, seed = seedForAttempt(initial, quest, true))

    val event = handler.handle(
        GameplayWireCommand.QuestAction("route-explore-cmd", "p2", "EXPLORE_SITE", quest.questId, 1),
        1_000,
    )

    assertEquals("EXPLORE_SITE", event.payload["meta.questFieldAction"])
    assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
}
```

Mirror for `SEARCH_SUPPLIES`.

Add:

```kotlin
test("field action rejects forged amount other than one without mutation") { ... }
```

and verify manual `PROGRESS` fails for EXPLORE/COLLECT while RESCUE/ESCORT/INVESTIGATE still route and progress.

Regression calls must explicitly assert `START_HUNT` still creates HUNT binding/combat and `START_BOSS` still creates BOSS binding/combat.

- [ ] **Step 2: Add negative integration routing tests**

Using handler commands, prove these do not change field quest progress:

```kotlin
GameplayWireCommand.WorldAction(..., "SHOP_BUY", "bandage", 1)
GameplayWireCommand.InventoryAction(..., "USE", "bandage", 1)
```

For direct `InventoryEngine.grant`/scenario reward/campaign arrival, compare quest progress before/after the ordinary authoritative transition in the most local existing integration fixture. Do not insert objective routing into these paths.

- [ ] **Step 3: Run core suite to observe RED**

```bash
bash tools/run-core-tests.sh
```

Expected: unknown quest actions because handler does not own field coordinator yet.

- [ ] **Step 4: Implement handler ownership and routing**

Add imports and instance:

```kotlin
private val questFieldCoordinator = QuestFieldCoordinator(
    hostReplica = hostReplica,
    campaignSeed = seed,
    snapshotStore = snapshotStore,
    durableStore = durableStore,
)
```

Extend `coordinatorOwnsFingerprint` for `QuestAction` whose uppercase action is `EXPLORE_SITE` or `SEARCH_SUPPLIES`, so exact retries are returned by the coordinator fingerprint rather than the generic `quest-action|...` fingerprint path.

In the QuestAction routing block:

```kotlin
when (command.actionType.uppercase()) {
    "START_BOSS" -> return questBossCoordinator.start(...)
    "START_HUNT" -> return questHuntCoordinator.start(...)
    "EXPLORE_SITE" -> {
        require(command.amount == 1) { "Field quest action amount must be exactly one" }
        return questFieldCoordinator.attempt(command.commandId, command.actorId, command.questId, QuestFieldActionType.EXPLORE_SITE, hostTimestamp)
    }
    "SEARCH_SUPPLIES" -> {
        require(command.amount == 1) { "Field quest action amount must be exactly one" }
        return questFieldCoordinator.attempt(command.commandId, command.actorId, command.questId, QuestFieldActionType.SEARCH_SUPPLIES, hostTimestamp)
    }
    else -> return applyQuestAction(before, command, fingerprint, hostTimestamp)
}
```

Do not modify `GameplayWireCommand`, `WireCodec`, `Protocol.kt` or subtype assignments.

- [ ] **Step 5: Run full core suite for GREEN**

```bash
bash tools/run-core-tests.sh
```

Expected: new routing tests + HUNT/BOSS/wire codec regressions pass.

- [ ] **Step 6: Commit Task 4**

```bash
git add core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestFieldRoutingTest.kt \
  core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: route field quest actions authoritatively"
```

---

### Task 5: Present Field Actions, Energy Requirements and Last D20; Preserve SOLO Semantics

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`

**Interfaces:**
- Consumes: `QuestFieldState.readLast(...)`, existing generic `QUEST` action dispatch.
- Produces: correct EXPLORE/COLLECT actions and authoritative result text; no new screen/dispatcher.

- [ ] **Step 1: Write presenter RED tests**

Replace the old migration test with one proving only RESCUE/ESCORT/INVESTIGATE keep `PROGRESS`.

Add:

```kotlin
test("explore and collect expose field actions and never manual progress") {
    val explore = quest("explore", QuestRarity.COMMON, 3, QuestType.EXPLORE)
    val collect = quest("collect", QuestRarity.COMMON, 4, QuestType.COLLECT)
    val world = profiledWorld().copy(
        questBoard = QuestBoardState(active = mapOf(
            explore.questId to QuestProgress(explore, QuestStatus.ACTIVE, 0, "p1"),
            collect.questId to QuestProgress(collect, QuestStatus.ACTIVE, 1, "p2"),
        )),
    )
    val view = GamePresenter.presentQuests(world, "p1")
    assertTrue(view.actions.any { it.id == "EXPLORE_SITE|explore|1" && it.label == "Explorar ruínas • Contrato explore" })
    assertTrue(view.actions.any { it.id == "SEARCH_SUPPLIES|collect|1" && it.label == "Continuar busca de suprimentos • Contrato collect" })
    assertTrue(view.actions.none { it.id == "PROGRESS|explore|1" || it.id == "PROGRESS|collect|1" })
}
```

Add zero-energy case: actor energy 0 => neither field action offered and body contains `1 PE` requirement.

Create authoritative last result via `QuestFieldState.writeAttemptResult(...)` and assert body contains exactly the key pieces:

```text
Último teste: P2 • INT + MEDICINE • d20 14 + 3 = 17 vs CD 15 • SUCESSO • -1 PE
```

Use a manually constructed `QuestFieldAttemptResult` to keep UI test deterministic.

READY EXPLORE/COLLECT must show `TURN_IN` and no field action.

- [ ] **Step 2: Add SOLO RED test**

In `GameSessionCoordinatorTest`, persist a SOLO hub world with one ACTIVE EXPLORE quest, P1/P2 complete, and known P1/P2 energy. Resume, call:

```kotlin
session.submitQuestAction("EXPLORE_SITE", quest.questId)
```

Assert P1 energy drops by one, P2 energy is unchanged, attempt count becomes one, and no automatic second field attempt occurs. This proves field actions are single-actor and the companion planner is not invoked to farm progress.

- [ ] **Step 3: Run core suite and observe RED**

```bash
bash tools/run-core-tests.sh
```

Expected: presenter still emits generic PROGRESS for EXPLORE/COLLECT and no last-roll line.

- [ ] **Step 4: Implement presenter behavior**

Inside ACTIVE quest action selection:

```kotlin
QuestType.EXPLORE -> if ((world.players[actorId]?.energy ?: 0) > 0) {
    val prefix = if (progress.progress == 0) "Explorar ruínas" else "Continuar exploração"
    add(GameAction("EXPLORE_SITE|${progress.definition.questId}|1", "$prefix • ${progress.definition.title}", "QUEST"))
}
QuestType.COLLECT -> if ((world.players[actorId]?.energy ?: 0) > 0) {
    val prefix = if (progress.progress == 0) "Buscar suprimentos" else "Continuar busca de suprimentos"
    add(GameAction("SEARCH_SUPPLIES|${progress.definition.questId}|1", "$prefix • ${progress.definition.title}", "QUEST"))
}
QuestType.RESCUE, QuestType.ESCORT, QuestType.INVESTIGATE ->
    add(GameAction("PROGRESS|${progress.definition.questId}|1", "Registrar progresso • ${progress.definition.title}", "QUEST"))
```

In the active quest body, append zero-PE hint for field types and append last roll when `QuestFieldState.readLast(...) != null`:

```kotlin
append("\nÚltimo teste: ${last.actorId.uppercase()} • ${last.checkId} • d20 ${last.roll} + ${last.modifier} = ${last.total} vs CD ${last.difficultyClass} • ${if (last.success) "SUCESSO" else "FALHA"} • -1 PE")
```

If modifier is negative, render the arithmetic without malformed `+ -1`; use a helper such as:

```kotlin
private fun signedAdd(value: Int): String = if (value >= 0) "+ $value" else "- ${-value}"
```

No new Android screen or action kind.

- [ ] **Step 5: Run full core suite for GREEN**

```bash
bash tools/run-core-tests.sh
```

Expected: presenter + SOLO tests pass; HUNT companion test remains green.

- [ ] **Step 6: Commit Task 5**

```bash
git add core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt \
  core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt \
  core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt
git commit -m "feat: present explore and collect field checks"
```

---

### Task 6: Prove EXPLORE/COLLECT Reconnect and Exactly-Once Semantics Over Real TCP

**Files:**
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldLanIntegrationTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: handler field routing, `LanHostServer`, `LanClientConnection`, `ClientReplica`, `SnapshotStore`, `CanonicalStateHasher`.
- Produces: end-to-end loopback TCP evidence; ideally no production code changes.

- [ ] **Step 1: Write the real TCP lifecycle test and register it**

Model the fixture after `QuestHuntLanIntegrationTest`: create host/client temp snapshot stores, host replica + real `LanHostServer`, P2 `LanClientConnection`, complete wounded players and enough energy (for example 40 PE each in the fixture, while retaining valid profile max values only if model invariants allow; otherwise use maxEnergy from a high-CON/VON test profile and an explicit safe attempt bound that fits it).

Activate:
- EXPLORE main + same-target EXPLORE sibling;
- COLLECT main + same-target COLLECT sibling.

Use a deterministic campaign seed selected before runtime so the sequence includes at least one failure and enough successes within a bounded number of attempts.

Test skeleton:

```kotlin
test("P2 field objectives reconnect converge and reward exactly once over real TCP") {
    // connect P2
    // send EXPLORE_SITE once
    // assert energy -1, attempt ordinal 1, last flags, progress, host/client hash convergence
    // disconnect P2
    // let host execute one authoritative field attempt while P2 snapshot is stale
    // recreate ClientReplica from stale clientStore snapshot and reconnect
    // assert exact restored energy/attempt/last flags/progress/hash
    // continue EXPLORE attempts until READY_TO_TURN_IN with <= SAFE_ATTEMPT_BOUND
    // assert sibling EXPLORE remains 0
    // continue COLLECT attempts until READY_TO_TURN_IN
    // assert sibling COLLECT remains 0
    // prove at least one accepted D20 failure was observed and spent energy without progress
    // perform SHOP_BUY bandage and assert COLLECT progress unchanged
    // TURN_IN both exactly once
    // retry exact turn-in command and one previous exact field-attempt command id
    // assert no duplicate reward, PE spend, ordinal or progress
    // assert host == client and canonical hashes + snapshots converge
}
```

Track successes based on authoritative quest progress, not assumed roll sequence. Use a loop with an explicit max such as 30 attempts per contract and fail the test if READY is not reached.

- [ ] **Step 2: Run the core suite**

```bash
bash tools/run-core-tests.sh
```

If GREEN immediately, Task 6 is test-only and production needs no reconnect changes. If it fails, invoke `superpowers:systematic-debugging` before modifying production and identify whether the bug is persistence, generic retry routing, field fingerprint ownership or fixture error.

- [ ] **Step 3: Verify persisted canonical convergence explicitly**

The test must contain:

```kotlin
assertEquals(host.state, clientReplica.state)
assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))
assertEquals(host.state, hostStore.loadLatestValid())
assertEquals(clientReplica.state, clientStore.loadLatestValid())
```

and exact field-state assertions after reconnect.

- [ ] **Step 4: Commit Task 6**

```bash
git add core/src/test/kotlin/grandlineduo/game/quest/QuestFieldLanIntegrationTest.kt \
  core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "test: cover field quest reconnect over tcp"
```

---

### Task 7: Final Regression, Compatibility, Android Build and PR Evidence

**Files:**
- Verify/no edit: `core/src/main/kotlin/grandlineduo/game/combat/CombatEngine.kt`
- Verify/no edit: `core/src/main/kotlin/grandlineduo/game/quest/QuestBossFactory.kt`
- Verify/no edit: `core/src/main/kotlin/grandlineduo/game/quest/QuestBossCoordinator.kt`
- Verify/no edit: `core/src/main/kotlin/grandlineduo/core/network/Protocol.kt`
- Verify/no edit: `core/src/main/kotlin/grandlineduo/core/network/WireCodec.kt`
- Verify/no edit: `core/src/main/kotlin/grandlineduo/core/persistence/WorldStateCodec.kt`
- Modify: `docs/superpowers/plans/2026-08-24-explore-collect-field-objectives.md`
- Modify: PR #4 body/title after observed evidence.

**Interfaces:**
- Consumes: all completed tasks.
- Produces: fresh exact-head evidence and clean open PR; no merge.

- [ ] **Step 1: Run fresh full core suite on the source head**

```bash
bash tools/run-core-tests.sh
```

Record the observed `RESULT N/N passed` exactly. Do not reuse earlier counts.

Explicitly inspect the output for passing HUNT, quest BOSS, PvP, arc/scenario, field coordinator and real TCP field reconnect tests.

- [ ] **Step 2: Verify compatibility invariants from source**

Confirm:

```text
PROTOCOL_VERSION == 5
QuestAction wire subtype == 9
DuelAction wire subtype == 10
WorldStateCodec CURRENT_VERSION == 11
```

Compare `CombatEngine.kt` blob to `main`; compare QuestBoss factory/coordinator blobs to pre-field head `00267140668b90c75a3268c3b72e3b21ebefea80`. They must be unchanged.

Also compare the field feature head against its baseline to prove no `GameplayWireCommand.kt`, `WireCodec.kt` or snapshot schema change unless a test-driven bug fix explicitly required it; such a change would violate the approved spec and must stop completion for design review.

- [ ] **Step 3: Build Android from the exact current source**

Preferred local commands:

```bash
gradle --no-daemon --stacktrace :app:assembleDebug
test -s app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

If the current environment cannot build Android locally, create a temporary PR workflow that checks out the exact source, runs `bash tools/run-core-tests.sh`, then `gradle --no-daemon --stacktrace :app:assembleDebug`, verifies non-empty APK and prints SHA-256. After success, remove the workflow and compare build-tested source to final head; only workflow removal + verification docs may differ, never `app/`/`core/`.

- [ ] **Step 4: Append observed verification evidence to this plan**

Add a section containing only observed values:

```markdown
## Observed Verification
- Final/source head: `<sha>`
- Core CI/run/job: `<observed ids>`
- Core result: `N/N passed`
- Android source head: `<sha>`
- Android run/job: `<observed ids>`
- Gradle: `BUILD SUCCESSFUL`
- APK SHA-256: `<observed hash>`
- Post-build diff: `<exact files>`; no `app/`/`core/` changes
```

Do not write placeholders in the committed final version; fill them only after values exist.

- [ ] **Step 5: Run final exact-head Core CI after documentation/workflow cleanup**

Require a successful Core CI associated with the final clean branch head. Read the job log and capture the exact `RESULT N/N passed` plus PR merge SHA checked out by Actions.

- [ ] **Step 6: Update PR #4 without merging**

Update title/body to include:
- automatic EXPLORE/COLLECT field objectives;
- D20 formulas and CD table;
- 1 PE per accepted attempt;
- visible last roll;
- no shop/inventory/arrival shortcuts;
- real TCP reconnect evidence;
- exact final Core + Android evidence;
- compatibility invariants;
- statement that PR remains intentionally open/unmerged.

Do not call merge or auto-merge.

- [ ] **Step 7: Final verification-before-completion gate**

Invoke `superpowers:verification-before-completion`. Verify the final head again before claiming completion. If any check is pending/failing, report partial completion rather than claiming success.

- [ ] **Step 8: Preserve branch**

Use `superpowers:finishing-a-development-branch`. The intended integration choice for this work is to keep `feature/quest-contract-system` / PR #4 open for later handling; do not merge or delete it without an explicit new user instruction.
