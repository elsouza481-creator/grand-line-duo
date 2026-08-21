# Quest Boss Combat Integration Implementation Plan

> **For implementation:** Execute this plan task-by-task with `superpowers:test-driven-development`, keep `feature/quest-contract-system` isolated from `main`, and use `superpowers:verification-before-completion` before any completion claim.

**Goal:** Turn accepted `QuestType.BOSS` contracts into deterministic authoritative boss fights that reuse the existing combat engine, persist through save/reconnect, support ordinary combat plus Haki/Akuma no Mi techniques, and progress/fail the quest only from the real combat result.

**Architecture:** Add a pure `QuestBossFactory` and a host-authoritative `QuestBossCoordinator`, with combat ownership persisted as `worldFlags["quest.boss.active"] = questId`. `StormglassGameplayCommandHandler` routes both `CombatAction` and prepared `PowerAction` to the quest coordinator when that binding exists; otherwise existing arc/scenario combat behavior remains unchanged. Android keeps using the existing `QUESTS` overlay and the already-versioned `QuestAction` wire command through a new `START_BOSS` action.

**Tech Stack:** Kotlin/JVM core, custom test registry, existing `CombatEngine`, `HostReplica`, durable/snapshot stores, real TCP LAN integration, Android/Kotlin Gradle build.

---

## Scope review

This is one coherent feature: all tasks below serve the single player flow `accepted BOSS contract -> explicit start -> authoritative combat -> quest outcome -> turn-in`. No task is an independent project that should be extracted.

---

### Task 1: Deterministic Quest Boss Factory

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestBossFactory.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestBossFactoryTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: `WorldState`, `QuestDefinition`, campaign seed.
- Produces: existing `CombatState` only; no persistence side effects.

- [x] **Step 1: Write the failing factory tests**
- [x] **Step 2: Run Core CI to verify RED**
- [x] **Step 3: Implement the minimal factory**
- [x] **Step 4: Run Core CI to verify GREEN**

Verification: Core CI #371 (`32519943500`) -> `299/299 passed`.

---

### Task 2: Authoritative Quest Boss Coordinator and Boss-only Progress Gate

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestBossCoordinator.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestBossCoordinatorTest.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- `QuestBossCoordinator.start(commandId, actorId, questId, timestamp)`.
- `QuestBossCoordinator.submitAction(...)` for ordinary combat.
- `QuestBossCoordinator.submitPreparedAction(...)` for already-prepared power actions.
- `QuestEngine.progress(...)` rejects BOSS contracts.
- Trusted coordinator-only transition `QuestEngine.completeBossObjective(...)` marks a defeated BOSS objective ready for turn-in.

- [x] **Step 1: Write failing coordinator + BOSS progress tests**
- [x] **Step 2: Verify RED**
- [x] **Step 3: Implement coordinator and trusted boss completion**
- [x] **Step 4: Verify GREEN**

Verification: Core CI #376 (`32520283936`) -> `306/306 passed`.

---

### Task 3: Route START_BOSS, Basic Combat and Power Techniques by Persistent Origin

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestBossCoordinatorTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/powers/PowerCombatIntegrationTest.kt`

**Interfaces:**
- `QuestAction("START_BOSS", questId)` starts a validated contract boss.
- Active combat with `quest.boss.active` routes to `QuestBossCoordinator`.
- Prepared Haki/Akuma technique state is committed by the same authoritative quest-boss event.

- [x] **Step 1: Write routing RED tests**
- [x] **Step 2: Verify RED contains only expected routing failures**
- [x] **Step 3: Implement source-aware routing**
- [x] **Step 4: Verify GREEN and arc regression safety**

Verification: Core CI #383 (`32521741952`) -> `309/309 passed`.

---

### Task 4: Quest Presentation and Solo Companion Coverage

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- Modify only if evidence requires it: `core/src/main/kotlin/grandlineduo/appshell/GameSessionCoordinator.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`

**Interfaces:**
- Active BOSS contract shows `START_BOSS|<questId>|1` / `Enfrentar alvo` instead of manual `PROGRESS`.
- Existing solo `autoPlayCompanion()` is reused for quest boss combat.

- [x] **Step 1: Write presentation RED**
- [x] **Step 2: Implement minimal presenter behavior**
- [x] **Step 3: Add solo session coverage before changing solo production logic**
- [x] **Step 4: Keep coordinator unchanged when test proves current AI already works**

Verification: Core CI #385 (`32522505236`) -> `311/311 passed`.

---

### Task 5: Real TCP Reconnect and Reward Idempotency

**Files:**
- Modify: `core/src/test/kotlin/grandlineduo/game/quest/QuestLanIntegrationTest.kt`
- Production changes only if the test exposes an evidence-backed gap.

**Interfaces:**
- P2 accepts/starts BOSS through real TCP.
- Reconnect restores bound combat and locked action state.
- Victory converges canonical state/hash.
- Repeated identical TURN_IN command grants reward once.

- [x] **Step 1: Add end-to-end TCP test**
- [x] **Step 2: Run full Core CI**
- [x] **Step 3: Make no production change because existing implementation passes**

Verification: Core CI #386 (`32522652510`) -> `312/312 passed`, including `P2 quest boss survives reconnect converges and rewards exactly once over real TCP`.

---

### Task 6: Final Regression, Android Build and PR Update

**Files:**
- `app/src/main/kotlin/com/grandlineduo/app/MainActivity.kt` inspected; no source change required for START_BOSS dispatch.
- PR #4 description updated through GitHub metadata.
- Temporary Android verification workflow created and removed.

**Interfaces:**
- Existing Android QUEST parser remains generic:

```kotlin
val parts = action.id.split('|', limit = 3)
val actionType = parts[0]
val questId = parts.getOrElse(1) { "" }
val amount = parts.getOrNull(2)?.toIntOrNull() ?: 1
coordinator.submitQuestAction(actionType, questId, amount)
```

- [x] **Step 1: Confirm Android dispatcher needs no source change**
- [x] **Step 2: Run final Core CI on exact clean branch head**
- [x] **Step 3: Build Android from current PR merge source**
- [x] **Step 4: Remove temporary workflow and prove source identity**
- [ ] **Step 5: Update PR #4 description**
- [ ] **Step 6: Final review before reporting completion**

Final core verification:
- head: `3570ab011074e9c91e7c2d1b373d2f47a11651d4`
- Core CI #389, run `32523025558`, job `96899174025`
- `RESULT 312/312 passed`.

Android verification:
- build-tested feature head: `2467585dd4205f6575a0e1ce8468bc9c788dcf3e`
- PR merge source during build: `4c1e3e965aaf043be5f36e7ec2f35838c7e8b566`
- Android verify run `32522843853`, job `96898602382`
- tests inside Android job: `RESULT 312/312 passed`
- `gradle --no-daemon --stacktrace :app:assembleDebug`
- `BUILD SUCCESSFUL in 44s`
- non-empty APK verified
- SHA-256: `80cb45cdb6c824616e05a052c4d346d34d529de79c574d2d8e497561a8b7924d`

Post-build source identity:
- cleanup head: `3570ab011074e9c91e7c2d1b373d2f47a11651d4`
- compare from build-tested head contains exactly one file change: removal of `.github/workflows/pr-current-source-android-verify.yml`
- no `app/` or `core/` source changed after the successful Android build.
