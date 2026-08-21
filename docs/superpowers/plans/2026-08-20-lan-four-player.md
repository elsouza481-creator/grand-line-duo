# Four-Player LAN Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand GRAND LINE DUO from one remote LAN peer to three concurrent remote peers (p2, p3, p4), then generalize gameplay participation milestone by milestone without breaking the existing P1/P2 game.

**Architecture:** P1 remains authoritative. `LanHostServer` owns one authenticated socket per remote player ID and all clients keep independent replicas synced from the same `HostReplica`. Protocol v6 advertises room capacity. Participant-dependent gameplay is generalized only after the transport is proven.

**Tech Stack:** Kotlin/JVM core, TCP sockets, UDP discovery, existing deterministic event log/hash/snapshot layer, Android app, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-20-lan-four-player-design.md`

## Global Constraints
- P1 remains authoritative.
- Maximum human slots are exactly p1, p2, p3, p4.
- Preserve existing event, hash, snapshot and idempotency semantics.
- Preserve all existing P1/P2 tests at every milestone.
- Protocol changes use `PROTOCOL_VERSION = 6`.
- Do not claim four-player gameplay until participant-dependent systems are generalized and tested.

---

### Task 1: Multi-peer TCP host

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/core/network/LanHostServer.kt`
- Modify: `core/src/test/kotlin/grandlineduo/core/network/LanTransportIntegrationTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Produces: `LanHostServer.activeClientIds: Set<String>`
- Produces: `LanHostServer.activeClientCount: Int`
- Keeps: `LanHostServer.hasActiveClient: Boolean`

- [ ] **Step 1: Write failing real-TCP test**

Add a test that creates one host and three `LanClientConnection` instances with peer IDs p2, p3 and p4. Connect all three and assert `activeClientIds == setOf("p2", "p3", "p4")` and `activeClientCount == 3`. Disconnect/reconnect p3 and assert p2/p4 stay active.

- [ ] **Step 2: Run Core CI and confirm RED**

Expected failure: constructor/server only accepts p2 and/or only exposes one active client.

- [ ] **Step 3: Implement peer-indexed sessions**

Use a synchronized mutable map from peer ID to socket. Default allowed IDs are `setOf("p2", "p3", "p4")`. On authenticated reconnect, replace only the existing socket for that peer. In `finally`, remove only if the map still points at the same socket.

- [ ] **Step 4: Run full suite**

Expected: all previous tests plus the new transport test pass.

- [ ] **Step 5: Commit**

Commit message: `feat: support three concurrent LAN peers`

### Task 2: Protocol v6 room capacity

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/core/network/Protocol.kt`
- Modify: `core/src/main/kotlin/grandlineduo/core/network/LanDiscovery.kt`
- Modify: `core/src/test/kotlin/grandlineduo/core/network/LanDiscoveryTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/core/network/WireCodecTest.kt` where version assertions exist

**Interfaces:**
- Produces: `LanDiscoveryAdvertisement.currentPlayers: Int`
- Produces: `LanDiscoveryAdvertisement.maxPlayers: Int`

- [ ] **Step 1: Write failing discovery codec tests**

Round-trip occupancy 3/4 and reject invalid occupancy such as 0/4, 5/4, or maxPlayers != 4.

- [ ] **Step 2: Verify RED**

Expected failure: occupancy fields do not exist and protocol remains v5.

- [ ] **Step 3: Implement protocol v6 and discovery fields**

Set `PROTOCOL_VERSION = 6`; encode/decode both occupancy integers under the existing packet checksum.

- [ ] **Step 4: Update host advertisement creation**

Populate currentPlayers from `1 + hostServer.activeClientCount` and maxPlayers = 4 whenever advertising.

- [ ] **Step 5: Run full suite and Android build**

Expected: green Core CI and source build.

### Task 3: Slot-aware coordinator/lobby

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GameSessionCoordinator.kt`
- Add/modify coordinator tests under `core/src/test/kotlin/grandlineduo/appshell/`

**Interfaces:**
- Produces: join flow selecting one free slot p2/p3/p4.
- Produces: host occupancy exposed to presentation layer.

- [ ] **Step 1: Write RED tests for three joining coordinators**

Require distinct actor IDs p2, p3, p4 and fourth remote join rejected as room full.

- [ ] **Step 2: Implement host-controlled slot allocation**

Persist slot identity locally for reconnect; do not infer p2 for every client.

- [ ] **Step 3: Verify reconnect keeps the same slot**

Disconnect p3, reconnect it, and prove p2/p4 remain connected and p3 retains identity.

- [ ] **Step 4: Run full suite and Android build**

### Task 4: Four player placeholders and character creation

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GameSessionCoordinator.kt`
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Modify relevant character/LAN integration tests

**Interfaces:**
- Host co-op world contains p1..p4 player slots.
- Handler validates actor IDs from present human slots rather than hard-coded p1/p2.

- [ ] **Step 1: RED for p3/p4 character creation over TCP**
- [ ] **Step 2: Generalize actor validation to present human players p1..p4**
- [ ] **Step 3: Verify all four profiles persist and hash identically across clients**
- [ ] **Step 4: Run full suite and Android build**

### Task 5: Exploration and quests for p1..p4

**Files:**
- Modify player-pair assumptions in exploration/presenter/quest systems only where tests identify them.
- Add p3/p4 TCP exploration and quest tests.

- [ ] **Step 1: RED: p3 and p4 move independently over TCP**
- [ ] **Step 2: RED: per-player quest state remains independent for all four**
- [ ] **Step 3: Generalize partner/pair UI assumptions to party lists**
- [ ] **Step 4: Full regression + Android build**

### Task 6: Dynamic-party PvE and voyages

**Files:**
- Modify combat/voyage participant readiness logic.
- Add 3-player and 4-player encounter tests.

- [ ] **Step 1: RED for dynamic set of alive combat participants**
- [ ] **Step 2: Resolve combat only after every required alive participant locks an action**
- [ ] **Step 3: Generalize voyage actions to required active human party slots**
- [ ] **Step 4: Verify reconnect of one player during a locked round**
- [ ] **Step 5: Full regression + Android build**

### Task 7: Targeted PvP and Android lobby/HUD

**Files:**
- Modify `TrainingDuelEngine` to store challenger/opponent IDs without `other(p1/p2)`.
- Modify presenter and Android lobby/HUD.

- [ ] **Step 1: RED for p1 challenging any one of p2/p3/p4**
- [ ] **Step 2: Implement explicit duel target selection**
- [ ] **Step 3: Show room occupancy 1/4..4/4 and connected player names**
- [ ] **Step 4: Full Core CI + Android APK**

## Verification before completion

For every milestone:
1. `tools/run-core-tests.sh` must pass completely in CI.
2. `.github/workflows/main-source-build.yml` must complete `assembleDebug` and artifact upload.
3. Record exact test count, commit SHA, artifact ID and digest before claiming the milestone green.
