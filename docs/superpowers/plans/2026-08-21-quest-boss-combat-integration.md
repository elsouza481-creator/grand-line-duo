# Quest Boss Combat Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make accepted `QuestType.BOSS` contracts start, persist, resolve and reward through real authoritative boss combat in solo and LAN co-op.

**Architecture:** Add a pure `QuestBossFactory` and a host-only `QuestBossCoordinator` that reuse `CombatEngine`, `CombatModifierResolver`, `HostReplica` and the existing persistence path. Bind a quest fight to the singular `WorldState.activeCombat` using the already-persisted `worldFlags["quest.boss.active"]`, then route ordinary and prepared power actions to the quest coordinator while leaving narrative arc combat unchanged.

**Tech Stack:** Kotlin 2.0.21 core, custom Kotlin test registry, Android/Kotlin app shell, GitHub Actions, Java 17, Gradle 9.5, real loopback TCP integration tests.

**Spec:** `docs/superpowers/specs/2026-08-21-quest-boss-combat-integration-design.md`

## Global Constraints

- Reuse the existing `CombatEngine`; do not create a second combat engine.
- Do not change narrative arc boss stats or responsibility.
- No free healing/checkpoints; quest bosses copy current player HP exactly.
- Exactly one quest boss may be active because `WorldState.activeCombat` is singular.
- Binding key is exactly `quest.boss.active`; no snapshot-version bump solely for combat origin.
- Rarity stats are fixed: COMMON 72/11, RARE 108/14, EPIC 150/18, LEGENDARY 200/22 for HP/attack.
- `START_BOSS` uses the existing `GameplayWireCommand.QuestAction`; no new wire subtype.
- Manual `PROGRESS` must reject active BOSS quests.
- Boss victory only moves the quest to `READY_TO_TURN_IN`; `TURN_IN` remains the only reward grant.
- Boss defeat permanently fails the quest id and records the failure reason only in event metadata.
- `CombatAction` and `PowerAction` must both route by `quest.boss.active` when `activeCombat != null`.
- Existing saves with no `quest.boss.active` flag and existing arc boss behavior must remain unchanged.

---

### Task 1: Deterministic Quest Boss Factory

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestBossFactory.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestBossFactoryTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: `WorldState`, `QuestDefinition`, `QuestType.BOSS`, `QuestRarity`, `CombatState`, `Combatant`, `EnemyCombatant`, `EnemyTelegraph`, `EnemyAttackType`.
- Produces: `QuestBossFactory.create(world: WorldState, quest: QuestDefinition, campaignSeed: Long): CombatState` and `QuestBossFactory.combatSeed(quest: QuestDefinition, campaignSeed: Long): Long`.

- [ ] **Step 1: Write failing factory tests and register them**

Create `QuestBossFactoryTest` with four tests:

```kotlin
object QuestBossFactoryTest {
    fun register() {
        test("quest boss factory is deterministic for identical inputs") {
            val world = world()
            val quest = boss("boss-common", QuestRarity.COMMON)
            assertEquals(
                QuestBossFactory.create(world, quest, 44L),
                QuestBossFactory.create(world, quest, 44L),
            )
        }

        test("quest boss rarity maps to exact hp and attack tiers") {
            val expected = mapOf(
                QuestRarity.COMMON to (72 to 11),
                QuestRarity.RARE to (108 to 14),
                QuestRarity.EPIC to (150 to 18),
                QuestRarity.LEGENDARY to (200 to 22),
            )
            expected.forEach { (rarity, stats) ->
                val state = QuestBossFactory.create(world(), boss("boss-${rarity.name}", rarity), 77L)
                assertEquals(stats.first, state.enemy.maxHp)
                assertEquals(stats.second, state.enemy.attackPower)
            }
        }

        test("quest boss carries current player health without healing") {
            val state = QuestBossFactory.create(
                world().copy(players = mapOf(
                    "p1" to PlayerState("p1", "Kairo", 9, 30, 1L),
                    "p2" to PlayerState("p2", "Namiya", 4, 28, 1L),
                )),
                boss("boss-wounded", QuestRarity.RARE),
                12L,
            )
            assertEquals(9, state.players.getValue("p1").hp)
            assertEquals(4, state.players.getValue("p2").hp)
        }

        test("quest boss uses target id as stable enemy identity") {
            val quest = boss("boss-target", QuestRarity.EPIC).copy(targetId = "island-enforcer")
            assertEquals("island-enforcer", QuestBossFactory.create(world(), quest, 91L).enemy.id)
        }
    }
}
```

Register `grandlineduo.game.quest.QuestBossFactoryTest.register()` beside the other quest tests in `TestRunner.main()`.

- [ ] **Step 2: Run the full core suite and verify RED**

Run: `bash tools/run-core-tests.sh`

Expected: compilation failure for unresolved `QuestBossFactory` (or failing newly registered factory tests), proving the new contract is not implemented yet.

- [ ] **Step 3: Implement the pure factory minimally**

Create `QuestBossFactory.kt`:

```kotlin
object QuestBossFactory {
    fun create(world: WorldState, quest: QuestDefinition, campaignSeed: Long): CombatState {
        require(quest.type == QuestType.BOSS) { "Quest is not a boss contract" }
        require(quest.islandId == world.islandId) { "Boss quest is not on the current island" }
        val p1 = world.players["p1"] ?: throw IllegalArgumentException("Missing p1")
        val p2 = world.players["p2"] ?: throw IllegalArgumentException("Missing p2")
        val (hp, attack) = stats(quest.rarity)
        val random = Random(combatSeed(quest, campaignSeed))
        val target = if (random.nextBoolean()) "p1" else "p2"
        val type = if (random.nextBoolean()) EnemyAttackType.HEAVY_STRIKE else EnemyAttackType.SWEEP
        val name = quest.title.substringAfter(": ").ifBlank { quest.targetId.replace('-', ' ') }
        return CombatState(
            round = 1,
            players = mapOf(
                "p1" to Combatant("p1", p1.name, p1.hp, p1.maxHp),
                "p2" to Combatant("p2", p2.name, p2.hp, p2.maxHp),
            ),
            enemy = EnemyCombatant(quest.targetId, name, hp, hp, attack),
            telegraph = EnemyTelegraph(type, target),
            status = CombatStatus.ACTIVE,
        )
    }

    fun combatSeed(quest: QuestDefinition, campaignSeed: Long): Long =
        campaignSeed xor (quest.questId.hashCode().toLong() shl 17) xor quest.targetId.hashCode().toLong()

    private fun stats(rarity: QuestRarity): Pair<Int, Int> = when (rarity) {
        QuestRarity.COMMON -> 72 to 11
        QuestRarity.RARE -> 108 to 14
        QuestRarity.EPIC -> 150 to 18
        QuestRarity.LEGENDARY -> 200 to 22
    }
}
```

- [ ] **Step 4: Run the suite and verify GREEN for the factory**

Run: `bash tools/run-core-tests.sh`

Expected: all existing tests plus the four factory tests pass.

- [ ] **Step 5: Commit Task 1**

```bash
git add core/src/main/kotlin/grandlineduo/game/quest/QuestBossFactory.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestBossFactoryTest.kt \
  core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: add deterministic quest boss factory"
```

---

### Task 2: Host-Authoritative Quest Boss Lifecycle

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestBossCoordinator.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestBossCoordinatorTest.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: `QuestBossFactory.create`, `QuestBossFactory.combatSeed`, `CombatEngine`, `CombatModifierResolver.forWorld(world)`, `HostReplica`, `SnapshotStore`, `DurableCampaignStore`, `QuestEngine.progress`, `QuestEngine.fail`.
- Produces:
  - `QuestBossCoordinator.start(commandId: String, playerId: String, questId: String, hostTimestamp: Long): CampaignEvent`
  - `QuestBossCoordinator.submitAction(commandId: String, playerId: String, actionType: CombatActionType, hostTimestamp: Long): CampaignEvent`
  - `QuestBossCoordinator.submitPreparedAction(commandId: String, playerId: String, actionType: CombatActionType, preparedWorld: WorldState, sourceFingerprint: String, metadata: Map<String, String>, hostTimestamp: Long): CampaignEvent`

- [ ] **Step 1: Add RED tests for BOSS manual progress rejection**

In `QuestEngineTest`, add a test that accepts a BOSS quest and calls `QuestEngine.progress(...)`:

```kotlin
var rejected = false
try { QuestEngine.progress(accepted, quest.questId, 1) }
catch (_: IllegalArgumentException) { rejected = true }
assertTrue(rejected)
assertEquals(0, accepted.questBoard.active.getValue(quest.questId).progress)
```

- [ ] **Step 2: Add RED coordinator tests and register them**

Create `QuestBossCoordinatorTest` covering:

```kotlin
test("accepted boss contract starts authoritative combat and binding") { ... }
test("non boss contract cannot start quest boss combat") { ... }
test("quest boss combat command retry is idempotent") { ... }
test("quest boss victory clears combat and makes contract ready to turn in") { ... }
test("quest boss defeat permanently fails contract without reward") { ... }
test("quest boss combat uses authoritative equipment modifiers") { ... }
```

Use a helper world with the quest already in `questBoard.active` and assert after `start(...)`:

```kotlin
assertEquals(quest.questId, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
assertEquals(CombatStatus.ACTIVE, host.state.activeCombat!!.status)
```

For victory, replace the started combat enemy HP with a low value and resolve a SETUP/FINISHER round; assert:

```kotlin
assertEquals(null, host.state.activeCombat)
assertEquals(null, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
assertEquals(QuestStatus.READY_TO_TURN_IN, host.state.questBoard.active.getValue(quest.questId).status)
assertTrue(quest.questId !in host.state.questBoard.completedQuestIds)
```

For defeat, create one living player at 1 HP, target that player with HEAVY_STRIKE, attack into a high-HP boss, then assert:

```kotlin
assertEquals(CombatStatus.DEFEAT, host.state.activeCombat!!.status)
assertTrue(quest.questId in host.state.questBoard.failedQuestIds)
assertTrue(quest.questId !in host.state.questBoard.active)
assertEquals(berriesBefore, host.state.partyBerries)
assertEquals("BOSS_DEFEAT", event.payload["meta.questFailure"])
```

Register `QuestBossCoordinatorTest.register()` in `TestRunner`.

- [ ] **Step 3: Run full suite and verify RED**

Run: `bash tools/run-core-tests.sh`

Expected: failures for missing `QuestBossCoordinator` plus the BOSS manual-progress test.

- [ ] **Step 4: Reject manual BOSS progress in `QuestEngine.progress`**

Immediately after loading `current`, add:

```kotlin
require(current.definition.type != QuestType.BOSS) {
    "Boss contracts progress only through boss victory"
}
```

Keep `turnIn`, reward application and non-BOSS progress unchanged.

- [ ] **Step 5: Implement `QuestBossCoordinator.start`**

Use constant:

```kotlin
const val ACTIVE_QUEST_FLAG = "quest.boss.active"
```

Validation in `start(...)` must require:

```kotlin
require(hostReplica.state.activeCombat == null) { "Combat is already active" }
require(hostReplica.state.activeVoyage == null) { "Cannot start quest boss during voyage" }
require(hostReplica.state.worldFlags[ACTIVE_QUEST_FLAG] == null) { "Quest boss combat is already bound" }
val progress = hostReplica.state.questBoard.active[questId]
    ?: throw IllegalArgumentException("Boss quest is not active: $questId")
require(progress.status == QuestStatus.ACTIVE) { "Boss quest is not active" }
require(progress.definition.type == QuestType.BOSS) { "Quest is not a boss contract" }
require(progress.definition.islandId == hostReplica.state.islandId) { "Boss quest is not on current island" }
```

Create `activeCombat = QuestBossFactory.create(...)`, set the flag atomically, submit through `ReplaceWorldStateCommand`, and metadata:

```kotlin
mapOf(
    "meta.questBoss" to "STARTED",
    "meta.questId" to questId,
    "meta.enemyId" to combat.enemy.id,
)
```

- [ ] **Step 6: Implement shared quest-boss round resolution**

Both `submitAction(...)` and `submitPreparedAction(...)` delegate to one private method receiving the source world, fingerprint and metadata. Validate the flag resolves to an active BOSS quest, then use:

```kotlin
val engine = CombatEngine(
    QuestBossFactory.combatSeed(progress.definition, campaignSeed),
    CombatModifierResolver.forWorld(sourceWorld),
)
val locked = engine.lockAction(current, CombatAction(playerId, actionType))
val result = engine.resolveIfReady(locked)
```

When unresolved, persist the locked state. When resolved, synchronize player HP exactly like `ArcCombatCoordinator`.

VICTORY:

```kotlin
var next = sourceWorld.copy(players = players, activeCombat = null, worldFlags = sourceWorld.worldFlags - ACTIVE_QUEST_FLAG)
next = QuestEngine.progress(next, questId, progress.definition.requiredAmount)
```

Because `QuestEngine.progress` now rejects BOSS manual progress, add an internal dedicated engine method for trusted combat completion instead of bypassing the invariant from outside:

```kotlin
fun completeBossObjective(world: WorldState, questId: String): WorldState
```

It must require `QuestType.BOSS`, `QuestStatus.ACTIVE`, and set progress to `requiredAmount` with `READY_TO_TURN_IN`. `QuestBossCoordinator` calls that method; public/manual `progress` remains rejected.

DEFEAT:

```kotlin
val failed = QuestEngine.fail(
    sourceWorld.copy(players = players, activeCombat = result.state, worldFlags = sourceWorld.worldFlags - ACTIVE_QUEST_FLAG),
    questId,
    "boss defeat",
)
metadata["meta.questFailure"] = "BOSS_DEFEAT"
```

- [ ] **Step 7: Verify Task 2 GREEN**

Run: `bash tools/run-core-tests.sh`

Expected: all factory, engine and coordinator lifecycle tests pass; all legacy arc combat tests remain green.

- [ ] **Step 8: Commit Task 2**

```bash
git add core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt \
  core/src/main/kotlin/grandlineduo/game/quest/QuestBossCoordinator.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestBossCoordinatorTest.kt \
  core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: resolve quest boss lifecycle authoritatively"
```

---

### Task 3: Gameplay Handler Routing for START_BOSS, Combat and Powers

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestBossCoordinatorTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/powers/PowerCombatIntegrationTest.kt`

**Interfaces:**
- Consumes: `GameplayWireCommand.QuestAction`, `GameplayWireCommand.CombatAction`, `GameplayWireCommand.PowerAction`, `PowerTechniqueEngine.prepare`, Task 2 coordinator interfaces.
- Produces: source-aware routing without changing `GameplayWireCommand` or `WireCodec` subtype numbering.

- [ ] **Step 1: Add RED handler tests for `START_BOSS` and source-aware basic combat**

Use `StormglassGameplayCommandHandler` with an accepted boss world and assert:

```kotlin
handler.handle(GameplayWireCommand.QuestAction("boss-start", "p2", "START_BOSS", quest.questId), 1_000)
assertEquals(quest.questId, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
handler.handle(GameplayWireCommand.CombatAction("boss-p1", "p1", "SETUP"), 1_001)
assertEquals(CombatActionType.SETUP, host.state.activeCombat!!.lockedActions.getValue("p1").type)
```

Also construct an invalid `quest.boss.active` flag and assert a combat command rejects without mutating the authoritative hash.

- [ ] **Step 2: Add RED power-action test for quest boss origin**

Extend `PowerCombatIntegrationTest` or coordinator test with a character that has an awakened usable technique. Start a quest boss, capture energy before, submit `GameplayWireCommand.PowerAction`, and assert:

```kotlin
assertTrue(host.state.players.getValue("p1").energy < energyBefore)
assertEquals(quest.questId, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
assertTrue(host.state.activeCombat!!.lockedActions.getValue("p1").type in setOf(
    CombatActionType.HAKI_BUSOSHOKU,
    CombatActionType.HAKI_KENBUNSHOKU,
    CombatActionType.HAKI_HAOSHOKU,
    CombatActionType.DEVIL_FRUIT,
))
```

- [ ] **Step 3: Run suite and verify RED**

Run: `bash tools/run-core-tests.sh`

Expected: `START_BOSS` is unknown and/or quest-bound combat/power routes still execute arc assumptions.

- [ ] **Step 4: Instantiate `QuestBossCoordinator` in handler**

Add:

```kotlin
private val questBossCoordinator = QuestBossCoordinator(
    hostReplica = hostReplica,
    campaignSeed = seed,
    snapshotStore = snapshotStore,
    durableStore = durableStore,
)
```

- [ ] **Step 5: Route `QuestAction.START_BOSS`**

Before generic `applyQuestAction` mutation, dispatch `START_BOSS` to:

```kotlin
return questBossCoordinator.start(
    command.commandId,
    command.actorId,
    command.questId,
    hostTimestamp,
)
```

Keep `REFRESH`, `ACCEPT`, non-BOSS `PROGRESS`, `TURN_IN` and `FAIL` in existing `applyQuestAction`.

- [ ] **Step 6: Route ordinary active combat by binding**

Replace the current unconditional arc route for `CombatAction && before.activeCombat != null` with:

```kotlin
return if (before.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] != null) {
    questBossCoordinator.submitAction(command.commandId, command.actorId, type, hostTimestamp)
} else {
    arcCombatCoordinator.submitAction(command.commandId, command.actorId, type, hostTimestamp)
}
```

Do not silently remove or ignore an invalid quest binding; `QuestBossCoordinator` validation must reject it.

- [ ] **Step 7: Route prepared power actions by binding**

Inside `applyPowerAction`, after:

```kotlin
val prepared = PowerTechniqueEngine.prepare(before, command.actorId, command.techniqueId)
val poweredWorld = prepared.world
```

and after building power metadata, if `poweredWorld.activeCombat != null && poweredWorld.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] != null`, return:

```kotlin
return questBossCoordinator.submitPreparedAction(
    commandId = command.commandId,
    playerId = command.actorId,
    actionType = prepared.combatAction,
    preparedWorld = poweredWorld,
    sourceFingerprint = fingerprint,
    metadata = metadata,
    hostTimestamp = hostTimestamp,
)
```

Leave the existing narrative arc and Stormglass power code path unchanged when the flag is absent.

- [ ] **Step 8: Verify Task 3 GREEN**

Run: `bash tools/run-core-tests.sh`

Expected: START_BOSS, basic combat, Haki/Devil Fruit quest-boss tests pass; existing `ArcCombatCoordinatorTest` and `PowerCombatIntegrationTest` legacy cases remain green.

- [ ] **Step 9: Commit Task 3**

```bash
git add core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt \
  core/src/test/kotlin/grandlineduo/game/quest/QuestBossCoordinatorTest.kt \
  core/src/test/kotlin/grandlineduo/game/powers/PowerCombatIntegrationTest.kt
git commit -m "feat: route quest boss combat and powers"
```

---

### Task 4: Quest Presentation and Solo Session Flow

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GameSessionCoordinator.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`

**Interfaces:**
- Consumes: `QuestType.BOSS`, `QuestStatus`, existing `GameAction` pipe-encoded quest action convention, `submitQuestAction`.
- Produces: `START_BOSS|<questId>|1` action labeled `Enfrentar alvo • <title>`; solo START_BOSS immediately presents combat and subsequent P1 combat automatically invokes the existing P2 `activeCombat` planner.

- [ ] **Step 1: Add RED presenter test**

Build a world with one active BOSS and one active HUNT quest. Assert:

```kotlin
val presentation = GamePresenter.presentQuests(world, "p1")
assertTrue(presentation.actions.any { it.id == "START_BOSS|${boss.questId}|1" })
assertTrue(presentation.actions.none { it.id == "PROGRESS|${boss.questId}|1" })
assertTrue(presentation.actions.any { it.id == "PROGRESS|${hunt.questId}|1" })
```

When the BOSS is `READY_TO_TURN_IN`, assert existing `TURN_IN` remains exposed instead.

- [ ] **Step 2: Add RED solo session integration test**

Use `GameSessionCoordinator.startSolo(...)`, create the P1 character so deterministic P2 exists, inject/arrive at a world state with an accepted BOSS using the existing authoritative test setup pattern, then:

```kotlin
coordinator.submitQuestAction("START_BOSS", boss.questId)
assertEquals(GameScreen.COMBAT, GamePresenter.present(coordinator.worldState(), "p1").screen)
val before = coordinator.worldState().activeCombat!!
coordinator.submitCombatAction(CombatActionType.SETUP)
val after = coordinator.worldState().activeCombat
assertTrue(after == null || after.round > before.round || "p2" in after.lockedActions)
```

The point is to prove the existing `autoPlayCompanion()` sees quest boss `activeCombat` without adding a second AI path.

- [ ] **Step 3: Run suite and verify RED**

Run: `bash tools/run-core-tests.sh`

Expected: presenter exposes generic PROGRESS instead of START_BOSS; session may need post-start companion handling only if the test demonstrates it.

- [ ] **Step 4: Update `GamePresenter.presentQuests` minimally**

Import `QuestType`. In `QuestStatus.ACTIVE`:

```kotlin
if (progress.definition.type == QuestType.BOSS) {
    if (world.activeCombat == null) {
        add(GameAction(
            "START_BOSS|${progress.definition.questId}|1",
            "Enfrentar alvo • ${progress.definition.title}",
            "QUEST",
        ))
    }
} else {
    add(GameAction(
        "PROGRESS|${progress.definition.questId}|1",
        "Registrar progresso • ${progress.definition.title}",
        "QUEST",
    ))
}
```

Do not create a new Android screen; `MainActivity` already parses arbitrary quest action ids into `submitQuestAction`.

- [ ] **Step 5: Adjust coordinator only if RED proves start needs solo post-processing**

`autoPlayCompanion()` already treats any `host.state.activeCombat` as tactical combat, so do not duplicate that logic. If the solo integration test requires immediate post-start processing, change only `submitQuestAction`:

```kotlin
if (mode == SessionMode.SOLO && action.equals("START_BOSS", ignoreCase = true)) {
    postProcessHostState()
}
```

Do not auto-submit a P2 move before P1 has locked a combat action.

- [ ] **Step 6: Verify Task 4 GREEN**

Run: `bash tools/run-core-tests.sh`

Expected: presenter BOSS/non-BOSS actions are correct, solo quest boss enters existing combat presentation, and campaign end-to-end remains green.

- [ ] **Step 7: Commit Task 4**

```bash
git add core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt \
  core/src/main/kotlin/grandlineduo/appshell/GameSessionCoordinator.kt \
  core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt \
  core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt
git commit -m "feat: expose quest boss combat flow"
```

---

### Task 5: Real TCP, Reconnect and Reward-Once Verification

**Files:**
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestLanIntegrationTest.kt`

**Interfaces:**
- Consumes: existing `LanHostServer`, `LanClientConnection`, `HostReplica`, `ClientReplica`, `SnapshotStore`, `StormglassGameplayCommandHandler`, `QuestAction`, `CombatAction`, Task 1-3 production paths.
- Produces: one end-to-end real TCP test proving quest boss authority survives reconnect and converges.

- [ ] **Step 1: Write a failing real-TCP quest boss lifecycle test**

Use an initial world whose board offers/contains an eligible BOSS quest. Through the client connection:

```kotlin
client.sendGameplay(GameplayWireCommand.QuestAction("boss-accept", "p2", "ACCEPT", quest.questId))
client.sendGameplay(GameplayWireCommand.QuestAction("boss-start", "p2", "START_BOSS", quest.questId))
assertEquals(quest.questId, host.state.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG])
assertEquals(host.state, clientReplica.state)
```

Lock one action, close the P2 connection, create a fresh `ClientReplica` from the last persisted client snapshot, reconnect, and assert the active combat plus flag match host state.

Drive deterministic SETUP/FINISHER or ATTACK rounds until victory with an explicit bounded loop (for example `repeat(20)`, failing if no victory). Then assert:

```kotlin
assertEquals(QuestStatus.READY_TO_TURN_IN, host.state.questBoard.active.getValue(quest.questId).status)
assertEquals(host.state, reconnectedReplica.state)
assertEquals(CanonicalStateHasher.hash(host.state), CanonicalStateHasher.hash(reconnectedReplica.state))
```

Finally turn in from P2 once, retry the exact same command id, and assert berries/rewards are not doubled.

- [ ] **Step 2: Run suite and verify the new integration test fails if any wiring is incomplete**

Run: `bash tools/run-core-tests.sh`

Expected before any required fix: a precise lifecycle/reconnect/reward assertion failure, not a compile error from unrelated code.

- [ ] **Step 3: Make only evidence-driven production fixes**

If the test exposes a real gap, change the smallest owning component from Tasks 2-3. Do not add LAN-specific quest boss state; all synchronization must continue through authoritative events/snapshots.

- [ ] **Step 4: Verify full core suite GREEN**

Run: `bash tools/run-core-tests.sh`

Expected: all tests pass, including the new real TCP quest boss lifecycle, existing quest TCP lifecycle, arc boss TCP tests, convergence simulations, campaign loop and persistence suites.

- [ ] **Step 5: Commit Task 5**

```bash
git add core/src/test/kotlin/grandlineduo/game/quest/QuestLanIntegrationTest.kt \
  core/src/main/kotlin/grandlineduo/game/quest/QuestBossCoordinator.kt \
  core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt
git commit -m "test: verify quest boss combat over LAN"
```

Only include production files in this commit if the TCP test required an evidence-driven correction.

---

### Task 6: Final Regression, Android Build and PR Update

**Files:**
- Modify only if needed: `app/src/main/kotlin/com/grandlineduo/app/MainActivity.kt`
- Modify: PR #4 description through GitHub metadata, not a repository file.
- Temporary verification workflow may be created and deleted if the existing branch cannot dispatch `main-source-build.yml` against PR source.

**Interfaces:**
- Consumes: entire feature branch, existing `MainActivity` quest parser (`action.id.split('|', limit = 3)`), GitHub Actions Core CI and Gradle Android build.
- Produces: fresh evidence that core tests pass and current PR source assembles a non-empty debug APK.

- [ ] **Step 1: Confirm Android dispatcher needs no source change**

Inspect `MainActivity` and verify existing QUEST dispatch remains generic:

```kotlin
val parts = action.id.split('|', limit = 3)
val actionType = parts[0]
val questId = parts.getOrElse(1) { "" }
val amount = parts.getOrNull(2)?.toIntOrNull() ?: 1
coordinator.submitQuestAction(actionType, questId, amount)
```

If unchanged, do not edit `MainActivity`; START_BOSS already flows through it.

- [ ] **Step 2: Run final Core CI on the exact branch head**

Trigger by pushing the final core/test commit and inspect the PR-associated `GRAND LINE DUO Core CI` run.

Expected log tail: `RESULT <N>/<N> passed` and job conclusion `success`.

- [ ] **Step 3: Build Android from current PR merge source**

Use a temporary PR workflow only if needed, with these exact verification commands:

```bash
bash tools/run-core-tests.sh
gradle --no-daemon --stacktrace :app:assembleDebug
test -s app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Expected: core suite success, `BUILD SUCCESSFUL`, non-empty APK and SHA-256 output.

- [ ] **Step 4: Remove temporary verification workflow and prove source identity**

If a temporary workflow was used, delete it. Compare the build-tested head with the post-cleanup head and require the only change to be deletion of that workflow; no `app/` or `core/` changes may occur after the verified build.

- [ ] **Step 5: Update PR #4 description**

Add a `Quest boss combat` section covering:

- BOSS contracts now require real deterministic combat;
- fixed rarity tiers;
- START_BOSS preparation window;
- ordinary and power action routing;
- victory -> READY_TO_TURN_IN, defeat -> failed history;
- solo AI and real TCP reconnect coverage;
- final core test count and run id;
- Android build result and APK SHA-256.

Keep the PR open and unmerged.

- [ ] **Step 6: Final review before reporting completion**

Confirm:

```text
PR state: open
merged: false
mergeable: true (or report GitHub's current value)
temporary workflows in PR diff: none
core CI: success on final source head
Android assembleDebug: success on same source, or a build-tested head whose only later diff is temp-workflow deletion
```
