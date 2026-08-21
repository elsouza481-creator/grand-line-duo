# Persistent Quest & Contract System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic, persistent, host-authoritative sandbox quest/contract system that supports optional shared objectives, rewards, failure history, Director-driven generation, and LAN commands.

**Architecture:** Quest domain logic is isolated under `grandlineduo.game.quest` with immutable definitions and a pure lifecycle engine. `WorldState.questBoard` carries authoritative shared quest state so existing host replacement, snapshots, reconnect, hashing, and LAN replication automatically include it. A Director bridge generates offers deterministically, while `GameplayWireCommand.QuestAction` and the existing gameplay handler expose host-authoritative mutations to either player.

**Tech Stack:** Kotlin 2.0 language mode, custom Kotlin test registry, Java data streams for snapshots/wire protocol, existing `WorldState`, `HostReplica`, `WireCodec`, `GrandLineDirector`, `InventoryEngine`, and `SocialState`.

**Spec:** `docs/superpowers/specs/2026-08-21-quest-contract-system-design.md`

## Global Constraints

- Exactly two human players: `p1` and `p2`.
- LAN gameplay remains host-authoritative and internet-independent.
- Quest generation must be deterministic and must not use wall-clock time or random UUIDs.
- Quest completion rewards must be idempotent: never applied twice.
- Versions 1-9 snapshots must decode with an empty quest board.
- Empty/default quest state must not change the legacy canonical hash.
- No quest-board mutation during active combat or voyage incidents.
- Existing tests may not be weakened or removed.

---

### Task 1: Quest domain model and lifecycle engine

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestState.kt`
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestEngine.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Produces `QuestType`, `QuestRarity`, `QuestStatus`, `QuestRequirement`, `QuestReward`, `QuestDefinition`, `QuestProgress`, `QuestBoardState`.
- Produces `QuestEngine.accept(world, questId, actorId)`, `progress(world, questId, amount)`, `turnIn(world, questId)`, and `fail(world, questId, reason)` returning `WorldState`.

- [ ] **Step 1: Write failing lifecycle tests**

Register tests proving acceptance moves an offered quest to active, progress clamps at required amount and becomes ready, turn-in moves to completed history, faction/Berries/evolution/item/flag rewards apply once, ineligible acceptance is rejected, and fail records permanent failed history.

- [ ] **Step 2: Run suite and verify RED**

Run: `bash tools/run-core-tests.sh`

Expected: compilation fails because quest domain types do not exist.

- [ ] **Step 3: Implement minimal quest state and lifecycle engine**

Use immutable data classes. Eligibility checks must support minimum faction standing, minimum total bounty, required world flag, required profession text, and required combat-style text. Reward application must clamp faction standing to `-100..100`, add evolution points only to players with a profile, grant item rewards to both players that exist, and apply reward world flags.

- [ ] **Step 4: Run suite and verify GREEN**

Run: `bash tools/run-core-tests.sh`

Expected: all existing tests plus quest lifecycle tests pass.

---

### Task 2: Deterministic Director-driven quest board generation

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/quest/QuestDirectorBridge.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestDirectorBridgeTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Produces `QuestDirectorBridge.refresh(world, seed, difficulty, presentFactions): WorldState`.
- Consumes `GrandLineDirector.threatBudget`, island id, bounty, social standing, world flags, and generation index.

- [ ] **Step 1: Write failing generation tests**

Tests must prove identical stable inputs produce identical three-offer boards, a changed generation index changes ids, low difficulty cannot emit legendary quests, brutal/high-bounty context can emit epic/legendary candidates, and completed/failed quest ids are never re-offered.

- [ ] **Step 2: Run suite and verify RED**

Run: `bash tools/run-core-tests.sh`

Expected: compilation fails because `QuestDirectorBridge` does not exist.

- [ ] **Step 3: Implement deterministic generation**

Create a small internal archetype catalog covering all seven quest types. Build stable ids from `islandId`, generation index, archetype id, and slot. Select candidates with `java.util.Random(seed xor generationIndex...)`; rarity ceiling derives from Director threat budget. Emit at most three offers and increment generation index once per refresh.

- [ ] **Step 4: Run suite and verify GREEN**

Run: `bash tools/run-core-tests.sh`

Expected: all tests pass.

---

### Task 3: WorldState snapshot and hash integration

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/core/model/WorldState.kt`
- Modify: `core/src/main/kotlin/grandlineduo/core/persistence/WorldStateCodec.kt`
- Modify: `core/src/main/kotlin/grandlineduo/core/hash/CanonicalStateHasher.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestPersistenceTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Adds `val questBoard: QuestBoardState = QuestBoardState()` to `WorldState`.
- Snapshot version becomes 10.

- [ ] **Step 1: Write failing persistence/hash tests**

Tests must prove a non-empty quest board round-trips through snapshot v10, version-9 bytes decode with default empty quest board, active quest state changes canonical hash, and a legacy world with the default empty board preserves its exact previous hash.

- [ ] **Step 2: Run suite and verify RED**

Run: `bash tools/run-core-tests.sh`

Expected: tests fail because quest state is not serialized/hashed.

- [ ] **Step 3: Implement snapshot v10**

Serialize generation index, offers, active quests, completed ids, and failed ids with strict count/value validation. `QuestDefinition` serialization includes type, rarity, issuer, target, required amount, requirement fields, reward fields, and optional expiry.

- [ ] **Step 4: Implement conditional canonical hash section**

Only append a quest section when `questBoard != QuestBoardState()`; sort all maps/sets/quest ids deterministically.

- [ ] **Step 5: Run suite and verify GREEN**

Run: `bash tools/run-core-tests.sh`

Expected: all tests pass and legacy hash expectations remain unchanged.

---

### Task 4: Quest wire command and host-authoritative LAN integration

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/core/network/GameplayWireCommand.kt`
- Modify: `core/src/main/kotlin/grandlineduo/core/network/WireCodec.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/quest/QuestLanIntegrationTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/core/network/WireCodecTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Adds `GameplayWireCommand.QuestAction(commandId, actorId, actionType, questId = "", amount = 1)` with fingerprint `quest-action|actor|action|questId|amount`.
- Adds gameplay wire subtype `9`.
- Handler actions: `REFRESH`, `ACCEPT`, `PROGRESS`, `TURN_IN`, `FAIL`.

- [ ] **Step 1: Write failing wire/LAN tests**

Add a wire round-trip test for `QuestAction`. Add an integration test where host generates a board, guest accepts/progresses/turns in through gameplay commands, event replication converges both replicas, and reward state is equal on host and guest.

- [ ] **Step 2: Run suite and verify RED**

Run: `bash tools/run-core-tests.sh`

Expected: compilation/test failure because quest wire commands are absent.

- [ ] **Step 3: Implement wire subtype 9**

Encode/decode command id, actor id, action type, quest id, and amount with the same framing/checksum as existing gameplay commands.

- [ ] **Step 4: Implement handler branch**

Before legacy scenario decoding, route `QuestAction` to `applyQuestAction`. Reject mutation during active combat/voyage. `REFRESH` calls `QuestDirectorBridge.refresh` with deterministic seed derived from handler seed and current generation index; all other actions call `QuestEngine`. Submit the next world through `ReplaceWorldStateCommand` with metadata keys `meta.questAction` and `meta.questId`, then persist the event.

- [ ] **Step 5: Run suite and verify GREEN**

Run: `bash tools/run-core-tests.sh`

Expected: full suite passes.

---

### Task 5: Verification and PR

**Files:**
- Modify only files needed to fix verification defects.

- [ ] **Step 1: Run full core suite**

Run: `bash tools/run-core-tests.sh`

Expected: all tests pass.

- [ ] **Step 2: Confirm branch diff is quest-scoped**

Compare `feature/quest-contract-system` to `main`; no unrelated automation or gameplay refactors should be present.

- [ ] **Step 3: Open PR**

Title: `feat: add persistent sandbox quest contracts`

PR body must summarize lifecycle, deterministic generation, snapshot v10 compatibility, LAN host authority, and verification evidence.

- [ ] **Step 4: Inspect CI and fix any failures**

Core CI must complete successfully before the branch is considered ready for integration.