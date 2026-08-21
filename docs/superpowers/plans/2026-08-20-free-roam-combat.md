# Free-Roam Exploration Combat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add deterministic hostile encounters to the physical island map that use the existing hardcore cooperative combat engine, powers, persistence and LAN authority.

**Architecture:** `WorldState.activeCombat` stays the only persisted combat state. Free-roam source identity lives in authoritative `worldFlags`; `ExplorationCombatEngine` owns deterministic encounter generation/start/victory reward, while `ExplorationCombatCoordinator` owns host-authoritative basic actions. Existing arc combat remains untouched when `activeArc != null`.

**Tech Stack:** Kotlin/JVM core, Android Kotlin UI, custom deterministic CombatEngine, GitHub Actions core test runner and Android Gradle build.

**Spec:** `docs/superpowers/specs/2026-08-20-free-roam-combat-design.md`

## Global Constraints

- Do not create a second combat rules engine.
- Do not require a snapshot schema bump; use existing `activeCombat` plus `worldFlags`.
- Client-supplied action amount or enemy values never control enemy stats, seed or rewards.
- Preserve existing arc boss behavior and all current tests.
- Free-roam combat must support P1/P2, real TCP replication, Haki/Akuma no Mi power actions and hardcore defeat.

---

### Task 1: Physical Enemy and Encounter Activation

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/world/ExplorationEngine.kt`
- Create: `core/src/main/kotlin/grandlineduo/game/world/ExplorationCombatEngine.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/world/ExplorationCombatEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Produces: `ExplorationEnemy`, `ExplorationMap.enemies`, `ExplorationCombatEngine.isDefeated`, `startIfEncountered`, `encounterId`, `combatSeed`, `completeVictory`.

- [ ] **Step 1: Write failing tests** proving deterministic enemy placement, non-overlap, movement onto live enemy starts `activeCombat`, movement elsewhere does not, and victory completion marks defeated/rewards once.
- [ ] **Step 2: Run `bash tools/run-core-tests.sh` in CI** and confirm failure is only missing exploration enemy/combat APIs.
- [ ] **Step 3: Implement minimal deterministic `ExplorationEnemy` and `ExplorationCombatEngine`**. Enemy stats derive only from island danger and deterministic map identity. Use one road tile south/east of the plaza that does not overlap existing physical entities.
- [ ] **Step 4: Re-run full core suite** and require all tests green before proceeding.

### Task 2: Host-Authoritative Free-Roam Basic Combat

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/world/ExplorationCombatCoordinator.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/world/ExplorationCombatCommandIntegrationTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: `ExplorationCombatEngine.combatSeed(world)` and `completeVictory(world)`.
- Produces: `ExplorationCombatCoordinator.submitAction(commandId, playerId, actionType, hostTimestamp): CampaignEvent`.

- [ ] **Step 1: Write failing integration tests**: authoritative `EXPLORE_MOVE` onto enemy starts combat, P1+P2 actions resolve one round, victory clears combat and reward cannot duplicate.
- [ ] **Step 2: Verify RED in full CI** with failure caused by missing coordinator/routing.
- [ ] **Step 3: Implement coordinator** using `CombatEngine(ExplorationCombatEngine.combatSeed(world), CombatModifierResolver.forWorld(world))`; synchronize player hp after resolved rounds; on victory call `completeVictory`; on defeat keep state.
- [ ] **Step 4: Update handler routing** so `activeArc != null` uses `ArcCombatCoordinator`, otherwise a valid exploration combat source uses the new coordinator. `EXPLORE_MOVE` calls `startIfEncountered` after movement.
- [ ] **Step 5: Run full core suite** and require all tests green.

### Task 3: Power Actions and Real TCP

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/world/ExplorationCombatLanIntegrationTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes existing `PowerTechniqueEngine.prepare` and `CombatEngine`.
- Produces source-aware active-combat resolution for both arc and exploration combat.

- [ ] **Step 1: Write failing tests** proving a free-roam Haki/power action no longer requires `activeArc`, and P2 basic combat over real TCP converges with host through a free-roam round.
- [ ] **Step 2: Verify RED** and confirm current failure is the `Active boss combat has no arc` assumption or missing free-roam LAN routing.
- [ ] **Step 3: Refactor only the active-combat branch of `applyPowerAction`**: arc victory keeps existing arc flag behavior; exploration victory calls `ExplorationCombatEngine.completeVictory`; all other combat calculations remain shared.
- [ ] **Step 4: Run full core suite** and require all tests green.

### Task 4: Map Presentation and Android Build

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- Modify: `app/src/main/kotlin/com/grandlineduo/app/ExplorationScreen.kt`

**Interfaces:**
- Produces: `ExplorationPresentation.visibleEnemies: Set<GridPosition>`.

- [ ] **Step 1: Write failing presenter test** proving an undefeated enemy is visible and a defeated enemy is hidden.
- [ ] **Step 2: Verify RED** due to missing `visibleEnemies`.
- [ ] **Step 3: Implement presenter projection** from `map.enemies` filtered by `ExplorationCombatEngine.isDefeated`.
- [ ] **Step 4: Render visible enemies in Android map** as red `X` markers and update accessibility description.
- [ ] **Step 5: Run full Core CI and current-source Android build**; require zero failed core tests and successful APK assembly/upload.
