# EXPLORE + COLLECT Field Objectives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert EXPLORE and COLLECT contracts from manual progress into deterministic host-authoritative D20 field actions with 1 PE cost, visible roll state, exact objective routing, persistence, idempotency and real TCP reconnect coverage.

**Architecture:** Extend the existing `QuestObjectiveRouter` to authorize EXPLORE/COLLECT objective events, add a pure `QuestFieldResolver` for D20 rules, and add a `QuestFieldCoordinator` for atomic energy/state/persistence. Keep `GameplayWireCommand.QuestAction` subtype 9, protocol v5 and snapshot v11 unchanged; handler and presenter gain only new action strings.

**Tech Stack:** Kotlin/JVM core module, custom Kotlin test runner, existing `HostReplica`/`ReplaceWorldStateCommand`, snapshot/event persistence, loopback TCP LAN tests, Android Gradle build.

**Spec:** `docs/superpowers/specs/2026-08-24-explore-collect-field-objectives-design.md`

## Global Constraints

- `WorldStateCodec.CURRENT_VERSION` remains **11**; no structured snapshot field is added.
- `PROTOCOL_VERSION` remains **5**.
- `GameplayWireCommand.QuestAction` remains wire subtype **9**; `DuelAction` remains subtype **10**.
- `CombatEngine.kt`, `QuestBossFactory.kt` and `QuestBossCoordinator.kt` remain unchanged.
- HUNT behavior remains unchanged and green.
- Field state lives only in existing `worldFlags` plus existing quest progress/player energy.
- Every accepted EXPLORE/COLLECT field attempt spends exactly **1 PE**, success or failure.
- Rewards remain exclusively behind existing `TURN_IN`.
- RESCUE/ESCORT/INVESTIGATE retain manual `PROGRESS` in this slice.
- Shop buys, inventory grants/rewards, campaign arrival, voyage completion and ordinary story/arc choices do not progress EXPLORE/COLLECT.
- Production code is written only after its corresponding RED test is observed.

---

## File Structure

**Create**
- `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldResolver.kt` — field-action type, result model, D20/CD/modifier/seed/progress rules.
- `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldState.kt` — exact world-flag key/read/write/cleanup helpers.
- `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldCoordinator.kt` — host authority, validation, PE spend, objective application, metadata, idempotency and persistence.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldResolverTest.kt`.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldCoordinatorTest.kt`.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldRoutingTest.kt`.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldLanIntegrationTest.kt`.

**Modify**
- `core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt`.
- `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`.
- `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`.
- `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`.
- `core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt`.
- `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`.
- `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`.
- `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`.
- This plan, after verification, with the exact observed CI/build evidence.
- PR #4 title/body, only after final evidence exists.

---

### Task 1: Authorize EXPLORE/COLLECT Objective Progress and Router Events

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/quest/QuestObjectiveRouter.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestObjectiveRouterTest.kt`

**Interfaces:**
- Consumes: existing `QuestEngine.progress`, `QuestEngine.progressObjective`, `QuestObjectiveEvent`.
- Produces: `QuestObjectiveEventType.LOCATION_VISITED`; objective progress for ACTIVE HUNT/EXPLORE/COLLECT; manual progress only for RESCUE/ESCORT/INVESTIGATE.

- [ ] **Step 1: Write failing QuestEngine authorization tests**

Replace the current migration expectations with these exact cases:

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
            requiredAmount = 3,
        )
        val accepted = QuestEngine.accept(worldWithOffer(quest), quest.questId, "p1")
        val progressed = QuestEngine.progress(accepted, quest.questId, 1)
        assertEquals(1, progressed.questBoard.active.getValue(quest.questId).progress)
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

Keep the existing ACTIVE-to-READY/clamp assertion and extend it once with EXPLORE or COLLECT to prove the shared transition is unchanged.

- [ ] **Step 2: Write failing exact router tests**

Generalize the test helper to:

```kotlin
private fun objective(
    id: String,
    type: QuestType,
    target: String,
    required: Int,
    status: QuestStatus = QuestStatus.ACTIVE,
    progress: Int = 0,
): QuestProgress = QuestProgress(
    definition = QuestDefinition(
        questId = id,
        islandId = "shells-town",
        title = "Contrato $id",
        type = type,
        rarity = QuestRarity.COMMON,
        issuerFaction = "CIVILIANS",
        targetId = target,
        requiredAmount = required,
    ),
    status = status,
    progress = progress,
    acceptedBy = "p1",
)
```

Add these tests:

```kotlin
test("location visited advances only exact bound explore") {
    val a = objective("explore-a", QuestType.EXPLORE, "forgotten-ruins", 3)
    val b = objective("explore-b", QuestType.EXPLORE, "forgotten-ruins", 3)
    val world = worldWith(a, b)
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

test("item acquired advances only exact bound collect") {
    val a = objective("collect-a", QuestType.COLLECT, "medical-supplies", 4)
    val b = objective("collect-b", QuestType.COLLECT, "medical-supplies", 4)
    val world = worldWith(a, b)
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

test("field objective router rejects wrong source target island type and ready state") {
    val explore = objective("explore-a", QuestType.EXPLORE, "forgotten-ruins", 3)
    val collect = objective("collect-a", QuestType.COLLECT, "medical-supplies", 4)
    val readyExplore = objective("explore-ready", QuestType.EXPLORE, "forgotten-ruins", 3, QuestStatus.READY_TO_TURN_IN, 3)
    val world = worldWith(explore, collect, readyExplore)

    assertEquals(world, QuestObjectiveRouter.apply(world, QuestObjectiveEvent(QuestObjectiveEventType.LOCATION_VISITED, "forgotten-ruins", "shells-town", 1, "missing")))
    assertEquals(world, QuestObjectiveRouter.apply(world, QuestObjectiveEvent(QuestObjectiveEventType.LOCATION_VISITED, "other-place", "shells-town", 1, "explore-a")))
    assertEquals(world, QuestObjectiveRouter.apply(world, QuestObjectiveEvent(QuestObjectiveEventType.LOCATION_VISITED, "forgotten-ruins", "other-island", 1, "explore-a")))
    assertEquals(world, QuestObjectiveRouter.apply(world, QuestObjectiveEvent(QuestObjectiveEventType.ITEM_ACQUIRED, "forgotten-ruins", "shells-town", 1, "explore-a")))
    assertEquals(world, QuestObjectiveRouter.apply(world, QuestObjectiveEvent(QuestObjectiveEventType.LOCATION_VISITED, "forgotten-ruins", "shells-town", 1, "explore-ready")))
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

- [ ] **Step 3: Run RED**

```bash
bash tools/run-core-tests.sh
```

Expected: compile/test failures because `LOCATION_VISITED` does not exist and EXPLORE/COLLECT authorization is still old.

- [ ] **Step 4: Implement QuestEngine authorization**

```kotlin
require(current.definition.type in setOf(QuestType.RESCUE, QuestType.ESCORT, QuestType.INVESTIGATE)) {
    "${current.definition.type.name} contracts progress only through authoritative objectives"
}
```

and:

```kotlin
require(current.definition.type in setOf(QuestType.HUNT, QuestType.EXPLORE, QuestType.COLLECT)) {
    "Objective progress is not enabled for ${current.definition.type.name}"
}
```

Do not change the shared `advance` function.

- [ ] **Step 5: Implement typed router mapping**

Add `LOCATION_VISITED` immediately after `ISLAND_VISITED` and map:

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

Keep sorted iteration and exact status/island/target/source matching.

- [ ] **Step 6: Run GREEN**

```bash
bash tools/run-core-tests.sh
```

Expected: full suite passes.

- [ ] **Step 7: Commit**

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
- Consumes: `WorldState`, `QuestProgress`, character attributes/skills.
- Produces: `QuestFieldActionType`, `QuestFieldAttemptResult`, `QuestFieldResolver.resolve`, `rollSeed`, `difficultyClass`, `progressPerSuccess`.

- [ ] **Step 1: Write resolver RED tests and register the test object**

Use these exact production types:

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

In the test file, define a deterministic world helper:

```kotlin
private fun fieldWorld(per: Int, intelligence: Int, skills: Map<Skill, Int>): WorldState {
    val created = CharacterCreation.create(CharacterCreationTest.validDraft()) as CharacterCreationResult.Success
    val profile = created.profile.copy(
        attributes = created.profile.attributes + mapOf(Attribute.PER to per, Attribute.INT to intelligence),
        skills = skills,
    )
    return WorldState(
        campaignId = "field-resolver",
        islandId = "shells-town",
        players = mapOf(
            "p1" to PlayerState("p1", "P1", 30, 30, 0, 20, 20, profile),
            "p2" to PlayerState("p2", "P2", 30, 30, 0, 20, 20, profile),
        ),
    )
}

private fun activeQuest(type: QuestType, rarity: QuestRarity = QuestRarity.COMMON): QuestProgress = QuestProgress(
    QuestDefinition(
        questId = "field-${type.name.lowercase()}",
        islandId = "shells-town",
        title = "Field ${type.name}",
        type = type,
        rarity = rarity,
        issuerFaction = "CIVILIANS",
        targetId = if (type == QuestType.EXPLORE) "forgotten-ruins" else "medical-supplies",
        requiredAmount = if (type == QuestType.EXPLORE) 3 * (rarity.ordinal + 1) else 4 * (rarity.ordinal + 1),
    ),
    QuestStatus.ACTIVE,
    0,
    "p1",
)
```

Register `QuestFieldResolverTest.register()` in `TestRunner.kt` immediately after `QuestObjectiveRouterTest.register()`.

Add tests:

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

test("explore uses perception plus best skill with fixed tie priority") {
    val world = fieldWorld(3, 1, mapOf(Skill.PERCEPTION to 2, Skill.SURVIVAL to 2, Skill.INVESTIGATION to 2))
    val result = QuestFieldResolver.resolve(world, activeQuest(QuestType.EXPLORE), "p1", QuestFieldActionType.EXPLORE_SITE, 1, 11L)
    assertEquals("PER + PERCEPTION", result.checkId)
    assertEquals(5, result.modifier)
}

test("collect chooses numerically best check with fixed tie priority") {
    val world = fieldWorld(2, 3, mapOf(Skill.SURVIVAL to 2, Skill.MEDICINE to 1, Skill.INVESTIGATION to 1))
    val result = QuestFieldResolver.resolve(world, activeQuest(QuestType.COLLECT), "p1", QuestFieldActionType.SEARCH_SUPPLIES, 1, 12L)
    assertEquals("PER + SURVIVAL", result.checkId)
    assertEquals(4, result.modifier)
}

test("identical authoritative inputs yield identical field result") {
    val world = fieldWorld(2, 2, mapOf(Skill.PERCEPTION to 1))
    val progress = activeQuest(QuestType.EXPLORE, QuestRarity.EPIC)
    val a = QuestFieldResolver.resolve(world, progress, "p2", QuestFieldActionType.EXPLORE_SITE, 4, 99L)
    val b = QuestFieldResolver.resolve(world, progress, "p2", QuestFieldActionType.EXPLORE_SITE, 4, 99L)
    assertEquals(a, b)
    assertTrue(a.roll in 1..20)
    assertEquals(a.roll + a.modifier, a.total)
    assertEquals(a.total >= a.difficultyClass, a.success)
}
```

Seed identity test:

```kotlin
test("quest target rarity action actor attempt and campaign seed participate in roll seed") {
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

Add one test that EXPLORE + `SEARCH_SUPPLIES` fails and COLLECT + `EXPLORE_SITE` fails.

- [ ] **Step 2: Run RED**

```bash
bash tools/run-core-tests.sh
```

Expected: unresolved field resolver/action/result symbols.

- [ ] **Step 3: Implement resolver rules**

Create the types above and:

```kotlin
object QuestFieldResolver {
    fun difficultyClass(rarity: QuestRarity): Int = when (rarity) {
        QuestRarity.COMMON -> 10
        QuestRarity.RARE -> 12
        QuestRarity.EPIC -> 15
        QuestRarity.LEGENDARY -> 18
    }

    fun progressPerSuccess(rarity: QuestRarity): Int = rarity.ordinal + 1

    fun rollSeed(
        progress: QuestProgress,
        actorId: String,
        actionType: QuestFieldActionType,
        attemptOrdinal: Int,
        campaignSeed: Long,
    ): Long {
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
}
```

Inside `resolve`, require ACTIVE status/current island/actor profile and exact action-to-type mapping. EXPLORE candidates, in tie order:

```kotlin
listOf(
    "PER + PERCEPTION" to (per + rank(Skill.PERCEPTION)),
    "PER + SURVIVAL" to (per + rank(Skill.SURVIVAL)),
    "PER + INVESTIGATION" to (per + rank(Skill.INVESTIGATION)),
)
```

COLLECT candidates, in tie order:

```kotlin
listOf(
    "PER + SURVIVAL" to (per + rank(Skill.SURVIVAL)),
    "INT + MEDICINE" to (intelligence + rank(Skill.MEDICINE)),
    "INT + INVESTIGATION" to (intelligence + rank(Skill.INVESTIGATION)),
)
```

Use `Random(rollSeed(...)).nextInt(20) + 1`; set objective type to `LOCATION_VISITED` for EXPLORE and `ITEM_ACQUIRED` for COLLECT. Natural 1/20 have no special rule.

- [ ] **Step 4: Run GREEN**

```bash
bash tools/run-core-tests.sh
```

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/grandlineduo/game/quest/QuestFieldResolver.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestFieldResolverTest.kt \
  core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: add deterministic quest field resolver"
```

---

### Task 3: Add Exact Field State and Atomic QuestFieldCoordinator

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldState.kt`
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestFieldCoordinator.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldCoordinatorTest.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: Tasks 1-2.
- Produces: `QuestFieldState` and `QuestFieldCoordinator.attempt(commandId, playerId, questId, actionType, hostTimestamp)`.

- [ ] **Step 1: Write RED tests for state, success, failure, idempotency and invalid states**

Register `QuestFieldCoordinatorTest.register()` after resolver tests.

Use a hub fixture with complete P1/P2 profiles, positive HP, known energy, `ScenarioStage.COMPLETE`, active field quest and no active combat/voyage/duel.

Define a deterministic seed selector:

```kotlin
private fun seedFor(
    world: WorldState,
    progress: QuestProgress,
    actorId: String,
    action: QuestFieldActionType,
    wantSuccess: Boolean,
): Long = (1L..10_000L).first { seed ->
    QuestFieldResolver.resolve(world, progress, actorId, action, 1, seed).success == wantSuccess
}
```

Required success/failure tests:

```kotlin
test("valid explore attempt spends one energy records state and emits exact progress on success") {
    val quest = exploreQuest("field-explore")
    val initial = hubWorld(quest, p1Energy = 7)
    val progress = initial.questBoard.active.getValue(quest.questId)
    val seed = seedFor(initial, progress, "p1", QuestFieldActionType.EXPLORE_SITE, true)
    val host = HostReplica(initial)
    val event = QuestFieldCoordinator(host, seed).attempt("field-explore-cmd", "p1", quest.questId, QuestFieldActionType.EXPLORE_SITE, 1_000)

    assertEquals(6, host.state.players.getValue("p1").energy)
    assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
    assertEquals(1, host.state.questBoard.active.getValue(quest.questId).progress)
    assertEquals("LOCATION_VISITED", event.payload["meta.questObjective"])
    assertEquals("1", event.payload["meta.questFieldEnergySpent"])
}

test("failed collect attempt spends one energy records roll and gives zero progress") {
    val quest = collectQuest("field-collect-fail")
    val initial = hubWorld(quest, p2Energy = 6)
    val progress = initial.questBoard.active.getValue(quest.questId)
    val seed = seedFor(initial, progress, "p2", QuestFieldActionType.SEARCH_SUPPLIES, false)
    val host = HostReplica(initial)
    val event = QuestFieldCoordinator(host, seed).attempt("field-collect-fail-cmd", "p2", quest.questId, QuestFieldActionType.SEARCH_SUPPLIES, 2_000)

    assertEquals(5, host.state.players.getValue("p2").energy)
    assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
    assertEquals(0, host.state.questBoard.active.getValue(quest.questId).progress)
    assertEquals("false", event.payload["meta.questFieldSuccess"])
    assertEquals(null, event.payload["meta.questObjective"])
}
```

Add same-target isolation by keeping a sibling active and proving its progress remains zero after a success.

Add either-player test where `acceptedBy = "p1"` but P2 successfully attempts.

Add retry test:

```kotlin
val first = coordinator.attempt("same-command", "p2", quest.questId, QuestFieldActionType.SEARCH_SUPPLIES, 3_000)
val afterFirst = host.state
val retry = coordinator.attempt("same-command", "p2", quest.questId, QuestFieldActionType.SEARCH_SUPPLIES, 3_001)
assertEquals(first.eventId, retry.eventId)
assertEquals(afterFirst, host.state)
```

Add command collision by reusing the same command id with a different action/quest and assert failure with unchanged state.

Add final-success test by starting an EXPLORE at progress 2/3 or COLLECT at 3/4 and choosing a success seed; assert READY_TO_TURN_IN.

For invalid-state table, construct each exact invalid world and assert rejection + no mutation for: missing profile, P1 HP 0, P2 HP 0, actor energy 0, wrong island, READY quest, mismatched action, structured combat, legacy combat, voyage, duel, HUNT binding, BOSS binding, incomplete arc, incomplete scenario when no arc exists.

- [ ] **Step 2: Write RED exact cleanup tests in QuestEngineTest**

The state helper API is:

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

object QuestFieldState {
    fun attemptCount(world: WorldState, questId: String): Int
    fun readLast(world: WorldState, questId: String): QuestFieldLastResult?
    fun writeAttemptResult(world: WorldState, questId: String, result: QuestFieldAttemptResult): WorldState
    fun clear(world: WorldState, questId: String): WorldState
}
```

Test two quests with field flags. Turn in only quest A and prove A keys are gone while B keys remain. Repeat using `QuestEngine.fail` for quest A. Also assert resolving a HUNT with no field flags changes no unrelated flags.

- [ ] **Step 3: Run RED**

```bash
bash tools/run-core-tests.sh
```

Expected: unresolved `QuestFieldState` and `QuestFieldCoordinator`.

- [ ] **Step 4: Implement exact QuestFieldState keys**

Use these exact key builders:

```kotlin
private fun attemptKey(id: String) = "quest.field.attempt.$id"
private fun actorKey(id: String) = "quest.field.last.actor.$id"
private fun actionKey(id: String) = "quest.field.last.action.$id"
private fun checkKey(id: String) = "quest.field.last.check.$id"
private fun rollKey(id: String) = "quest.field.last.roll.$id"
private fun modifierKey(id: String) = "quest.field.last.modifier.$id"
private fun totalKey(id: String) = "quest.field.last.total.$id"
private fun dcKey(id: String) = "quest.field.last.dc.$id"
private fun successKey(id: String) = "quest.field.last.success.$id"
```

`writeAttemptResult` writes all nine keys from the result. `clear` removes exactly those nine constructed keys; never scan/suffix-match arbitrary world flags.

- [ ] **Step 5: Add QuestEngine cleanup**

After the normal `turnIn` reward transition, return `QuestFieldState.clear(rewarded, questId)`. Build the normal failed world in `fail`, then return `QuestFieldState.clear(failed, questId)`.

- [ ] **Step 6: Implement QuestFieldCoordinator**

Constructor/API:

```kotlin
class QuestFieldCoordinator(
    private val hostReplica: HostReplica,
    private val campaignSeed: Long,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    @Synchronized
    fun attempt(
        commandId: String,
        playerId: String,
        questId: String,
        actionType: QuestFieldActionType,
        hostTimestamp: Long,
    ): CampaignEvent
}
```

Fingerprint:

```kotlin
val fingerprint = "quest-field|$playerId|${actionType.name}|$questId"
```

Before any mutation validate:

```kotlin
require(playerId == "p1" || playerId == "p2") { "Unknown player $playerId" }
val world = hostReplica.state
val restored = StormglassPersistenceAdapter.decode(world)
require(world.players["p1"]?.profile != null && world.players["p2"]?.profile != null) { "Both characters must be created before field actions" }
require((world.players["p1"]?.hp ?: 0) > 0 && (world.players["p2"]?.hp ?: 0) > 0) { "Both players must be alive for field actions" }
require((world.players[playerId]?.energy ?: 0) >= 1) { "Field action requires 1 PE" }
require(world.activeCombat == null && restored.combat == null) { "Field action unavailable during combat" }
require(world.activeVoyage == null) { "Field action unavailable during voyage" }
require(world.activeDuel == null) { "Field action unavailable during duel" }
require(world.worldFlags[QuestHuntCoordinator.ACTIVE_QUEST_FLAG] == null) { "Field action unavailable during hunt binding" }
require(world.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] == null) { "Field action unavailable during boss binding" }
require(world.activeArc == null || world.activeArc.phase == ArcPhase.COMPLETE) { "Field action requires completed arc" }
if (world.activeArc == null) require(restored.scenario.stage == ScenarioStage.COMPLETE) { "Field action requires completed scenario" }
```

Validate active quest/status/current island/action-type mapping. `acceptedBy` does not restrict the actor.

Atomic state change:

```kotlin
val ordinal = QuestFieldState.attemptCount(world, questId) + 1
val resolved = QuestFieldResolver.resolve(world, progress, playerId, actionType, ordinal, campaignSeed)
val actor = world.players.getValue(playerId)
var next = world.copy(players = world.players + (playerId to actor.copy(energy = actor.energy - 1)))
next = QuestFieldState.writeAttemptResult(next, questId, resolved)
if (resolved.success) {
    next = QuestObjectiveRouter.apply(
        next,
        QuestObjectiveEvent(
            resolved.objectiveEventType,
            resolved.targetId,
            resolved.islandId,
            resolved.progressAmount,
            questId,
        ),
    )
}
```

Commit one `ReplaceWorldStateCommand` only. Use existing coordinator `existing`/`persist` pattern. Metadata always contains action, quest id, attempt, actor, check, roll, modifier, total, dc, success and energy spent. On success add objective event/source/target/amount and resulting quest progress.

- [ ] **Step 7: Run GREEN**

```bash
bash tools/run-core-tests.sh
```

- [ ] **Step 8: Commit**

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

### Task 4: Route Field Actions Through Existing QuestAction Wire Subtype

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldRoutingTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: Task 3 coordinator, existing `GameplayWireCommand.QuestAction`.
- Produces: handler routes for `EXPLORE_SITE` and `SEARCH_SUPPLIES`; no wire schema change.

- [ ] **Step 1: Write routing RED tests and register them**

Register `QuestFieldRoutingTest.register()` after `QuestHuntRoutingTest.register()`.

Use a hub fixture with an ACTIVE field quest and helper that selects a deterministic success seed using `QuestFieldResolver`.

```kotlin
test("handler routes explore site through existing quest action") {
    val quest = fieldQuest("route-explore", QuestType.EXPLORE)
    val initial = hubWorld(quest)
    val seed = seedFor(initial, initial.questBoard.active.getValue(quest.questId), "p2", QuestFieldActionType.EXPLORE_SITE, true)
    val host = HostReplica(initial)
    val event = StormglassGameplayCommandHandler(host, seed).handle(
        GameplayWireCommand.QuestAction("route-explore-cmd", "p2", "EXPLORE_SITE", quest.questId, 1),
        1_000,
    )
    assertEquals("EXPLORE_SITE", event.payload["meta.questFieldAction"])
    assertEquals(1, QuestFieldState.attemptCount(host.state, quest.questId))
}

test("handler routes search supplies through existing quest action") {
    val quest = fieldQuest("route-collect", QuestType.COLLECT)
    val initial = hubWorld(quest)
    val seed = seedFor(initial, initial.questBoard.active.getValue(quest.questId), "p1", QuestFieldActionType.SEARCH_SUPPLIES, true)
    val host = HostReplica(initial)
    val event = StormglassGameplayCommandHandler(host, seed).handle(
        GameplayWireCommand.QuestAction("route-collect-cmd", "p1", "SEARCH_SUPPLIES", quest.questId, 1),
        2_000,
    )
    assertEquals("SEARCH_SUPPLIES", event.payload["meta.questFieldAction"])
}

test("handler rejects field amount other than one without mutation") {
    val quest = fieldQuest("route-amount", QuestType.EXPLORE)
    val initial = hubWorld(quest)
    val host = HostReplica(initial)
    val result = runCatching {
        StormglassGameplayCommandHandler(host, 701L).handle(
            GameplayWireCommand.QuestAction("route-amount-cmd", "p1", "EXPLORE_SITE", quest.questId, 2),
            3_000,
        )
    }
    assertTrue(result.isFailure)
    assertEquals(initial, host.state)
}
```

Add a retry test: send the identical field command twice; assert same event id, no second PE spend and no second ordinal increment.

Add manual routing test: `PROGRESS` rejects EXPLORE/COLLECT but advances RESCUE/ESCORT/INVESTIGATE.

Add regression tests invoking `START_HUNT` and `START_BOSS` and asserting their respective bindings/combat are still created.

- [ ] **Step 2: Write negative integration tests for unrelated actions**

With an ACTIVE COLLECT quest, execute:

```kotlin
handler.handle(GameplayWireCommand.WorldAction("buy-bandage", "p1", "SHOP_BUY", "bandage", 1), 4_000)
```

and assert collect progress unchanged. Create a second world via `InventoryEngine.grant(initial, "p1", "bandage", 3)` and assert quest progress unchanged. With ACTIVE EXPLORE, execute a normal scenario/arc transition in its existing integration fixture and assert quest progress unchanged. Do not add objective calls to any of those paths.

- [ ] **Step 3: Run RED**

```bash
bash tools/run-core-tests.sh
```

Expected: unknown field quest actions or wrong fingerprint ownership.

- [ ] **Step 4: Implement handler ownership/routing**

Instantiate:

```kotlin
private val questFieldCoordinator = QuestFieldCoordinator(
    hostReplica = hostReplica,
    campaignSeed = seed,
    snapshotStore = snapshotStore,
    durableStore = durableStore,
)
```

Treat `EXPLORE_SITE`/`SEARCH_SUPPLIES` QuestAction as coordinator-owned fingerprints alongside START_HUNT/START_BOSS. Then route:

```kotlin
when (command.actionType.uppercase()) {
    "START_BOSS" -> return questBossCoordinator.start(command.commandId, command.actorId, command.questId, hostTimestamp)
    "START_HUNT" -> return questHuntCoordinator.start(command.commandId, command.actorId, command.questId, hostTimestamp)
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

Do not modify `GameplayWireCommand.kt`, `WireCodec.kt` or `Protocol.kt`.

- [ ] **Step 5: Run GREEN**

```bash
bash tools/run-core-tests.sh
```

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestFieldRoutingTest.kt \
  core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: route field quest actions authoritatively"
```

---

### Task 5: Present Field Actions, PE Requirement and Last D20; Preserve SOLO Behavior

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`

**Interfaces:**
- Consumes: `QuestFieldState.readLast` and existing generic `QUEST` dispatcher.
- Produces: correct actions/labels/body; no new screen or Android action kind.

- [ ] **Step 1: Write presenter RED tests**

```kotlin
test("explore and collect expose field actions without manual progress") {
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

Add partial EXPLORE label `Continuar exploração`, fresh COLLECT label `Buscar suprimentos`, READY-only TURN_IN, and remaining RESCUE/ESCORT/INVESTIGATE PROGRESS assertions.

Zero PE test:

```kotlin
test("zero energy hides field action and explains one pe requirement") {
    val explore = quest("explore-zero", QuestRarity.COMMON, 3, QuestType.EXPLORE)
    val base = profiledWorld()
    val p1 = base.players.getValue("p1")
    val world = base.copy(
        players = base.players + ("p1" to p1.copy(energy = 0)),
        questBoard = QuestBoardState(active = mapOf(explore.questId to QuestProgress(explore, QuestStatus.ACTIVE, 0, "p1"))),
    )
    val view = GamePresenter.presentQuests(world, "p1")
    assertTrue(view.actions.none { it.id.startsWith("EXPLORE_SITE|") })
    assertTrue(view.body.contains("1 PE"))
}
```

Last roll test: build a `QuestFieldAttemptResult` with roll 14, modifier 3, total 17, CD 15, success true, write it with `QuestFieldState.writeAttemptResult`, and assert body contains:

```text
Último teste: P2 • INT + MEDICINE • d20 14 + 3 = 17 vs CD 15 • SUCESSO • -1 PE
```

Add a negative modifier fixture and assert rendering uses `- 1`, not `+ -1`.

- [ ] **Step 2: Write SOLO RED test**

Persist/resume a SOLO hub world with ACTIVE EXPLORE, P1 energy 8 and P2 energy 9. Call:

```kotlin
session.submitQuestAction("EXPLORE_SITE", quest.questId)
```

Assert P1 energy becomes 7, P2 remains 9, attempt count is exactly 1, and there is only one new field-attempt event. This proves the companion planner does not spend P2 energy automatically.

- [ ] **Step 3: Run RED**

```bash
bash tools/run-core-tests.sh
```

- [ ] **Step 4: Implement presenter field behavior**

ACTIVE actions:

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

When an ACTIVE field quest is shown to an actor with 0 PE, append `\nRequer 1 PE para uma nova tentativa.`.

Append last roll for ACTIVE or READY field quests using `QuestFieldState.readLast`. Use:

```kotlin
private fun signedModifier(value: Int): String = if (value >= 0) "+ $value" else "- ${-value}"
```

and:

```kotlin
append("\nÚltimo teste: ${last.actorId.uppercase()} • ${last.checkId} • d20 ${last.roll} ${signedModifier(last.modifier)} = ${last.total} vs CD ${last.difficultyClass} • ${if (last.success) "SUCESSO" else "FALHA"} • -1 PE")
```

No session coordinator production change is expected; `submitQuestAction` already sends a single command and does not call `autoPlayCompanion`.

- [ ] **Step 5: Run GREEN**

```bash
bash tools/run-core-tests.sh
```

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt \
  core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt \
  core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt
git commit -m "feat: present explore and collect field checks"
```

---

### Task 6: Real TCP Reconnect, Failure Path and Exactly-Once Rewards

**Files:**
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestFieldLanIntegrationTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: field handler route, `LanHostServer`, `LanClientConnection`, `ClientReplica`, `SnapshotStore`, `CanonicalStateHasher`.
- Produces: end-to-end reconnect evidence; production should not change unless this test reveals a real bug.

- [ ] **Step 1: Register and write the loopback TCP test**

Register `QuestFieldLanIntegrationTest.register()` after `QuestHuntLanIntegrationTest.register()`.

Build a HOST_COOP hub fixture with:
- complete wounded P1/P2;
- `energy = maxEnergy = 40` for both test `PlayerState`s;
- ACTIVE EXPLORE main + same-target EXPLORE sibling;
- ACTIVE COLLECT main + same-target COLLECT sibling;
- scenario COMPLETE;
- normal shop/inventory state.

Pick a campaign seed by scanning `1L..10_000L` before server creation until the first bounded sequence contains at least one failure and enough successes for both contracts. The helper must inspect `QuestFieldResolver.resolve` using incrementing attempt ordinals and stop at 30 attempts.

Use this helper for repeated TCP attempts:

```kotlin
private fun attemptUntilReady(
    prefix: String,
    action: String,
    questId: String,
    host: HostReplica,
    client: LanClientConnection,
    clientReplica: ClientReplica,
    maxAttempts: Int = 30,
): List<Boolean> {
    val outcomes = mutableListOf<Boolean>()
    var step = 1
    while (host.state.questBoard.active.getValue(questId).status == QuestStatus.ACTIVE && step <= maxAttempts) {
        client.sendGameplay(GameplayWireCommand.QuestAction("$prefix-$step", "p2", action, questId, 1))
        outcomes += QuestFieldState.readLast(host.state, questId)!!.success
        assertEquals(host.state, clientReplica.state)
        assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))
        step++
    }
    assertEquals(QuestStatus.READY_TO_TURN_IN, host.state.questBoard.active.getValue(questId).status)
    return outcomes
}
```

Main test sequence:
1. Connect P2 from initial state.
2. P2 sends first EXPLORE_SITE attempt.
3. Assert host/client convergence for P2 energy, attempt count, last result, progress and canonical hash.
4. Disconnect P2.
5. Host executes one field attempt directly through handler while client snapshot is stale.
6. Recreate `ClientReplica` from `clientStore.loadLatestValid()` and reconnect.
7. Assert exact recovery of energy, attempt count, last flags, progress and hash.
8. Continue EXPLORE via `attemptUntilReady`; sibling EXPLORE stays 0.
9. Continue COLLECT via `attemptUntilReady`; sibling COLLECT stays 0.
10. Assert the combined outcome lists contain at least one `false` and required successful progress was reached.
11. Execute `SHOP_BUY bandage`; assert COLLECT progress unchanged.
12. TURN_IN EXPLORE and COLLECT; capture party Berries after each.
13. Send each exact TURN_IN command again; Berries do not change.
14. Resend one exact earlier field command id; energy, attempt count and progress do not change.
15. Assert host/client state + canonical hashes + host/client saved snapshots converge.

Use these final assertions:

```kotlin
assertEquals(host.state, clientReplica.state)
assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(clientReplica.state))
assertEquals(host.state, hostStore.loadLatestValid())
assertEquals(clientReplica.state, clientStore.loadLatestValid())
```

- [ ] **Step 2: Run full core suite**

```bash
bash tools/run-core-tests.sh
```

If it fails unexpectedly, invoke `superpowers:systematic-debugging` before modifying production. Identify whether the cause is test fixture, persistence, generic retry routing, field fingerprint ownership or state cleanup.

- [ ] **Step 3: Commit after GREEN**

```bash
git add core/src/test/kotlin/grandlineduo/game/quest/QuestFieldLanIntegrationTest.kt \
  core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "test: cover field quest reconnect over tcp"
```

---

### Task 7: Final Regression, Compatibility, Android Build and PR Evidence

**Files:**
- Verify unchanged: `core/src/main/kotlin/grandlineduo/game/combat/CombatEngine.kt`
- Verify unchanged: `core/src/main/kotlin/grandlineduo/game/quest/QuestBossFactory.kt`
- Verify unchanged: `core/src/main/kotlin/grandlineduo/game/quest/QuestBossCoordinator.kt`
- Verify constants: `core/src/main/kotlin/grandlineduo/core/network/Protocol.kt`
- Verify subtype assignments: `core/src/main/kotlin/grandlineduo/core/network/WireCodec.kt`
- Verify snapshot version: `core/src/main/kotlin/grandlineduo/core/persistence/WorldStateCodec.kt`
- Modify: this plan with exact observed verification values.
- Modify: PR #4 title/body after evidence exists.

**Interfaces:**
- Consumes: all completed tasks.
- Produces: fresh exact-head evidence and a clean open PR; no merge.

- [ ] **Step 1: Run a fresh full core suite on the source head**

```bash
bash tools/run-core-tests.sh
```

Copy the exact final `RESULT` line into the verification notes. Confirm the output contains passing HUNT, quest BOSS, PvP, arc/scenario, QuestFieldCoordinator and QuestFieldLanIntegration tests.

- [ ] **Step 2: Verify source compatibility invariants**

Read source and record:
- `PROTOCOL_VERSION` is 5.
- QuestAction encode/decode subtype is 9.
- DuelAction encode/decode subtype is 10.
- `WorldStateCodec.CURRENT_VERSION` is 11.

Compare `CombatEngine.kt` blob with `main`. Compare `QuestBossFactory.kt` and `QuestBossCoordinator.kt` blobs with baseline commit `00267140668b90c75a3268c3b72e3b21ebefea80`. They must match.

Compare baseline `926a89794f9fc1ac9be91c2496e98372cb7f42fe` to implementation source head and verify `GameplayWireCommand.kt`, `WireCodec.kt`, `Protocol.kt` and `WorldStateCodec.kt` were not modified. If any is modified, stop completion because it violates the approved spec.

- [ ] **Step 3: Build Android from the exact current source**

Preferred commands:

```bash
gradle --no-daemon --stacktrace :app:assembleDebug
test -s app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

If local Android build is unavailable, create the same temporary PR verification workflow pattern already used on this branch: checkout exact source, JDK 17, Gradle 9.5/Android SDK setup, run core suite, run `:app:assembleDebug`, verify non-empty APK and print SHA-256. After success, delete the temporary workflow.

- [ ] **Step 4: Append exact observed evidence to this plan**

Do not add a template. Write the actual branch/source SHA, Core Actions run number/id/job id/PR merge SHA, exact `RESULT` line, Android source SHA/run/job/PR merge SHA, exact `BUILD SUCCESSFUL` line, APK SHA-256 and exact post-build changed-file comparison. Each value must come from observed tool/log output in this execution.

- [ ] **Step 5: Prove no post-build code drift**

Compare the Android build-tested source SHA to the final clean head. The only allowed post-build changes are deletion of the temporary Android workflow and this verification-note update. Assert the comparison contains no file under `app/` or `core/`.

- [ ] **Step 6: Run final exact-head Core CI**

Require a successful Core CI associated with the final clean branch head. Read its job log and record the exact `RESULT` line and PR merge SHA checked out by Actions.

- [ ] **Step 7: Update PR #4 without merging**

Update title/body with the implemented EXPLORE/COLLECT field actions, exact D20 formulas/CDs, 1 PE rule, visible last roll, negative integration boundaries, real TCP reconnect result, exact final Core/Android evidence and compatibility invariants. State that the PR remains intentionally open/unmerged.

Do not call merge, auto-merge or delete the branch.

- [ ] **Step 8: Verification-before-completion and branch preservation**

Invoke `superpowers:verification-before-completion` and use fresh evidence from Steps 1-6. Then invoke `superpowers:finishing-a-development-branch`; preserve `feature/quest-contract-system` and PR #4 open unless the user gives a new explicit integration instruction.
