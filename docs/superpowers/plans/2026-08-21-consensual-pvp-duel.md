# Consensual PvP Duel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic, host-authoritative, reconnect-safe, non-lethal PvP duel between the two human GRAND LINE DUO players without changing the existing PvE combat engine.

**Architecture:** Introduce a separate `grandlineduo.game.duel` subsystem with persistent `DuelState`, pure `DuelEngine`, host-side `DuelCoordinator`, a dedicated `DuelAction` wire lifecycle command, and presenter/session integration. Ordinary duel rounds continue to reuse `CombatAction` and `PowerAction`, but `StormglassGameplayCommandHandler` routes them to the duel coordinator whenever `activeDuel` is ACTIVE; PvE boss/scenario combat remains untouched.

**Tech Stack:** Kotlin/JVM core, Android Kotlin app shell, custom deterministic test registry, binary `WorldStateCodec`, SHA-256 canonical hashing, existing `HostReplica`/`ClientReplica`/TCP LAN transport, Gradle Android build.

**Spec:** `docs/superpowers/specs/2026-08-21-consensual-pvp-duel-design.md`

## Global Constraints

- PvP is consensual and available only in two-human `HOST_COOP` campaigns; forged SOLO challenges must be rejected by host authority.
- The first PvP slice is non-lethal: a knockout ends at 1 HP, including both fighters at 1 HP for `DOUBLE_KNOCKOUT`.
- Entering, finishing, or closing a duel never heals HP, refunds energy, or grants berries, bounty, loot, reputation, quest progress, or quest rewards.
- Existing `CombatEngine` remains PvE-only; do not add PvP branches to it.
- Existing `CombatActionType`, `CombatModifiers`, `CombatModifierResolver`, and `PowerTechniqueEngine.prepare(...)` are reused.
- Opponent locked-action type must not be exposed by `GamePresenter` before the round resolves; only readiness may be shown.
- Duel state is structured persistent state, not a `worldFlags` encoding.
- Existing snapshot versions must remain readable; a world with `activeDuel == null` must retain the previous canonical hash representation.
- Adding the new gameplay wire subtype must not renumber subtypes 1-9; use subtype 10 and bump `PROTOCOL_VERSION` from 4 to 5 so old clients cannot silently join a duel-capable session.
- All production changes are developed TDD-first and committed in small, independently green increments.
- Final verification requires the complete core suite and `gradle --no-daemon --stacktrace :app:assembleDebug`.

---

## File Structure

**Create**

- `core/src/main/kotlin/grandlineduo/game/duel/DuelState.kt` — duel domain model and enums only.
- `core/src/main/kotlin/grandlineduo/game/duel/DuelEngine.kt` — pure deterministic simultaneous-round resolution.
- `core/src/main/kotlin/grandlineduo/game/duel/DuelStateBinaryCodec.kt` — binary snapshot encoding/decoding for duel state.
- `core/src/main/kotlin/grandlineduo/game/duel/DuelCanonicalState.kt` — stable canonical encoding used by the world hasher.
- `core/src/main/kotlin/grandlineduo/game/duel/DuelCoordinator.kt` — host authority for challenge lifecycle and active duel rounds.
- `core/src/test/kotlin/grandlineduo/game/duel/DuelEngineTest.kt` — engine behavior and deterministic balance rules.
- `core/src/test/kotlin/grandlineduo/game/duel/DuelCoordinatorTest.kt` — lifecycle, authority, powers, rewards, idempotency.
- `core/src/test/kotlin/grandlineduo/game/duel/DuelPersistenceTest.kt` — snapshot v11 round-trips and legacy v10 compatibility.
- `core/src/test/kotlin/grandlineduo/game/duel/DuelLanIntegrationTest.kt` — real TCP challenge/action/reconnect/convergence lifecycle.

**Modify**

- `core/src/main/kotlin/grandlineduo/core/model/WorldState.kt` — add `activeDuel: DuelState? = null`.
- `core/src/main/kotlin/grandlineduo/core/persistence/WorldStateCodec.kt` — snapshot version 11 and duel codec call.
- `core/src/main/kotlin/grandlineduo/core/hash/CanonicalStateHasher.kt` — append duel canonical data only when non-null.
- `core/src/main/kotlin/grandlineduo/core/network/GameplayWireCommand.kt` — add `DuelAction`.
- `core/src/main/kotlin/grandlineduo/core/network/WireCodec.kt` — gameplay subtype 10.
- `core/src/main/kotlin/grandlineduo/core/network/Protocol.kt` — `PROTOCOL_VERSION = 5`.
- `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt` — lifecycle and active duel routing with exclusive-state guard.
- `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt` — `DUEL` screen/pending/active/finished UI model and hub challenge action.
- `core/src/main/kotlin/grandlineduo/appshell/GameSessionCoordinator.kt` — `submitDuelAction` API and no SOLO companion participation.
- `app/src/main/kotlin/com/grandlineduo/app/MainActivity.kt` — dispatch `kind == "DUEL"`.
- `core/src/test/kotlin/grandlineduo/core/network/WireCodecTest.kt` — subtype 10 round-trip and old subtype regression.
- `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt` — challenge/pending/hidden-action/finished presentation.
- `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt` — host lifecycle API and SOLO rejection regression.
- `core/src/test/kotlin/grandlineduo/test/TestRunner.kt` — register the four new duel test objects.

---

### Task 1: Deterministic Duel Domain and Engine

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/duel/DuelState.kt`
- Create: `core/src/main/kotlin/grandlineduo/game/duel/DuelEngine.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/duel/DuelEngineTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: `CombatAction`, `CombatActionType`, `CombatModifiers`.
- Produces:
  - `enum class DuelPhase { PENDING, ACTIVE, FINISHED }`
  - `enum class DuelFinishReason { KNOCKOUT, DOUBLE_KNOCKOUT }`
  - `data class DuelFighter(val id: String, val name: String, val hp: Int, val maxHp: Int)`
  - `data class DuelState(...)`
  - `data class DuelRoundResult(val state: DuelState, val damageTaken: Map<String, Int>, val log: List<String>)`
  - `class DuelEngine(seed: Long, modifiers: Map<String, CombatModifiers> = emptyMap())`
  - `DuelEngine.lockAction(state: DuelState, action: CombatAction): DuelState`
  - `DuelEngine.resolveIfReady(state: DuelState): DuelRoundResult?`

- [ ] **Step 1: Write the failing engine tests and register them**

Create `DuelEngineTest` as an `object` with `fun register()` following the repository's custom test pattern. Cover these exact invariants:

```kotlin
val base = DuelState(
    duelId = "duel-1",
    challengerId = "p1",
    challengedId = "p2",
    phase = DuelPhase.ACTIVE,
    round = 1,
    fighters = mapOf(
        "p1" to DuelFighter("p1", "A", 60, 60),
        "p2" to DuelFighter("p2", "B", 60, 60),
    ),
)

val a = DuelEngine(99L).resolveIfReady(
    base.copy(lockedActions = mapOf(
        "p1" to CombatAction("p1", CombatActionType.ATTACK),
        "p2" to CombatAction("p2", CombatActionType.DEFEND),
    ))
)!!
val b = DuelEngine(99L).resolveIfReady(
    base.copy(lockedActions = mapOf(
        "p2" to CombatAction("p2", CombatActionType.DEFEND),
        "p1" to CombatAction("p1", CombatActionType.ATTACK),
    ))
)!!
assertEquals(a, b)
```

Also assert:
- second action by the same fighter in one round is rejected;
- `DEFEND` takes less damage than an undefended target under the same attacker/seed;
- deterministic `DODGE` can fully avoid a direct attack on a seed selected by the test;
- `HAKI_KENBUNSHOKU` has a higher avoidance threshold than ordinary `DODGE`;
- `SETUP` places only the actor into `setupReady` for the next round;
- a setup-backed `FINISHER` deals exactly 12 more pre-defense damage than the same raw finisher on the same deterministic stream;
- equipment `attackBonus` affects only that fighter's offensive actions;
- `damageReduction` affects only incoming damage to its owner;
- Busoshoku/Haoshoku/Devil Fruit add their matching modifier bonus;
- single knockout produces `FINISHED/KNOCKOUT`, loser HP 1, correct winner/loser ids;
- simultaneous lethal projections produce `FINISHED/DOUBLE_KNOCKOUT`, both HP 1, null winner/loser;
- no enemy, telegraph, or cooperative-combo data exists in the duel model.

Add `grandlineduo.game.duel.DuelEngineTest.register()` to `TestRunner.main()`.

- [ ] **Step 2: Run the suite to verify RED**

Run:

```bash
bash tools/run-core-tests.sh
```

Expected: compilation fails because `grandlineduo.game.duel` types do not exist yet.

- [ ] **Step 3: Implement the domain model**

Create `DuelState.kt` with this shape:

```kotlin
package grandlineduo.game.duel

import grandlineduo.game.combat.CombatAction

enum class DuelPhase { PENDING, ACTIVE, FINISHED }
enum class DuelFinishReason { KNOCKOUT, DOUBLE_KNOCKOUT }

data class DuelFighter(
    val id: String,
    val name: String,
    val hp: Int,
    val maxHp: Int,
)

data class DuelState(
    val duelId: String,
    val challengerId: String,
    val challengedId: String,
    val phase: DuelPhase,
    val round: Int = 0,
    val fighters: Map<String, DuelFighter> = emptyMap(),
    val lockedActions: Map<String, CombatAction> = emptyMap(),
    val setupReady: Set<String> = emptySet(),
    val winnerId: String? = null,
    val loserId: String? = null,
    val finishReason: DuelFinishReason? = null,
)

data class DuelRoundResult(
    val state: DuelState,
    val damageTaken: Map<String, Int>,
    val log: List<String>,
)

class DuelRuleException(message: String) : RuntimeException(message)
```

- [ ] **Step 4: Implement deterministic simultaneous resolution**

Use a fighter-specific random stream so map iteration order cannot change results:

```kotlin
private fun rng(round: Int, playerId: String, salt: Long): Random =
    Random(seed xor (round.toLong() * -7046029254386353131L) xor
        (playerId.hashCode().toLong() * 6364136223846793005L) xor salt)
```

Exact offensive rules before defense/damage reduction:

```text
ATTACK          = 14 + nextInt(5)
DEFEND          = 0
DODGE           = 0
SETUP           = 4 + nextInt(3)
FINISHER        = 14 + nextInt(6)
BUSOSHOKU       = 18 + nextInt(6) + busoshokuBonus
KENBUNSHOKU     = 0
HAOSHOKU        = 16 + nextInt(7) + haoshokuBonus
DEVIL_FRUIT     = 16 + nextInt(7) + devilFruitBonus
```

Apply `attackBonus` to `ATTACK`, `SETUP`, `FINISHER`, `HAKI_BUSOSHOKU`, `HAKI_HAOSHOKU`, and `DEVIL_FRUIT`.

If the attacker is in `state.setupReady`, add +12 to `FINISHER` or +6 to any other offensive action. Previous setup expires after this round regardless of chosen action. The next state's `setupReady` is exactly the set of fighters choosing `SETUP` in the current round.

Defense rules, evaluated on incoming damage before `damageReduction`:

```text
DEFEND: keep 35% of incoming damage, minimum 1 when incoming > 0.
DODGE: 65% deterministic avoidance against ATTACK/SETUP/FINISHER/BUSOSHOKU/DEVIL_FRUIT; not against HAOSHOKU.
KENBUNSHOKU: 85% deterministic avoidance against ATTACK/SETUP/FINISHER/BUSOSHOKU/DEVIL_FRUIT and 50% against HAOSHOKU; on a failed Kenbunshoku roll, keep 50% of incoming damage.
Other actions: full incoming damage.
```

After defense, subtract defender `damageReduction`, floor at 0. Calculate both fighters' projected HP from the pre-round state, then apply both results simultaneously. If both projections are <= 0, finish as `DOUBLE_KNOCKOUT` and set both to 1 HP. If one is <= 0, finish as `KNOCKOUT`, set only the loser to 1 HP, preserve the winner's simultaneous projected HP with minimum 1. Otherwise advance `round + 1`, clear `lockedActions`, and remain ACTIVE.

- [ ] **Step 5: Run the suite to verify GREEN**

Run `bash tools/run-core-tests.sh`.

Expected: all pre-existing tests plus `DuelEngineTest` pass.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/grandlineduo/game/duel/DuelState.kt \
        core/src/main/kotlin/grandlineduo/game/duel/DuelEngine.kt \
        core/src/test/kotlin/grandlineduo/game/duel/DuelEngineTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: add deterministic pvp duel engine"
```

---

### Task 2: Persistent Duel State and Canonical Hashing

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/duel/DuelStateBinaryCodec.kt`
- Create: `core/src/main/kotlin/grandlineduo/game/duel/DuelCanonicalState.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/duel/DuelPersistenceTest.kt`
- Modify: `core/src/main/kotlin/grandlineduo/core/model/WorldState.kt`
- Modify: `core/src/main/kotlin/grandlineduo/core/persistence/WorldStateCodec.kt`
- Modify: `core/src/main/kotlin/grandlineduo/core/hash/CanonicalStateHasher.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: Task 1 `DuelState`.
- Produces: `WorldState.activeDuel`, snapshot schema v11, deterministic duel canonical encoding.

- [ ] **Step 1: Write persistence/hash tests first**

Create `DuelPersistenceTest` and register it. Test:

```kotlin
val pending = world.copy(activeDuel = DuelState(
    duelId = "duel-pending",
    challengerId = "p2",
    challengedId = "p1",
    phase = DuelPhase.PENDING,
))
assertEquals(pending, WorldStateCodec.decode(WorldStateCodec.encode(pending)))
```

Add round-trips for:
- ACTIVE duel with fighters, one locked action, and `setupReady`;
- FINISHED knockout;
- FINISHED double knockout.

Add a legacy-v10 fixture test by encoding an equivalent pre-duel world with a test helper that writes version 10 layout or by retaining the existing v10 fixture bytes if the repository already has them. Assert decode gives `activeDuel == null`.

Hash assertions:

```kotlin
assertEquals(CanonicalStateHasher.hash(world), CanonicalStateHasher.hash(world.copy(activeDuel = null)))
assertNotEquals(CanonicalStateHasher.hash(world), CanonicalStateHasher.hash(pending))
```

Build two logically identical ACTIVE duels with reversed map insertion order and assert equal canonical hashes.

- [ ] **Step 2: Run RED**

Run `bash tools/run-core-tests.sh`.

Expected: compilation fails on missing `WorldState.activeDuel` / duel codecs.

- [ ] **Step 3: Add structured world state and v11 binary codec**

In `WorldState.kt` add:

```kotlin
val activeDuel: DuelState? = null,
```

Place it adjacent to `activeCombat` because both are exclusive tactical states.

In `WorldStateCodec`:
- set `CURRENT_VERSION = 11`;
- after quest board data and before players, write `data.writeBoolean(state.activeDuel != null)` and `DuelStateBinaryCodec.write(data, duel)`;
- on decode, read duel only when `version >= 11`; for 1-10 set null;
- pass `activeDuel` into reconstructed `WorldState`.

`DuelStateBinaryCodec` writes enum names and all maps in sorted player-id order. Validation limits are exactly two fighters, two locked actions, and two setup ids. Reject duplicate ids, non-P1/P2 participant ids, negative HP, HP above max HP, ACTIVE states without exactly both fighters, PENDING states with non-empty fighters/actions, and FINISHED states without a finish reason.

- [ ] **Step 4: Add conditional canonical duel encoding**

`DuelCanonicalState.encode(duel)` must include, in order:
- version marker `duelVersion=1`;
- duel id/challenger/challenged/phase/round;
- fighters sorted by player id;
- locked actions sorted by player id;
- setup ids sorted;
- winner/loser as empty string when null;
- finish reason as empty string when null.

`CanonicalStateHasher` appends this block only inside:

```kotlin
state.activeDuel?.let { append(DuelCanonicalState.encode(it)) }
```

Do not append any duel marker when `activeDuel == null`; this preserves legacy hashes.

- [ ] **Step 5: Run GREEN and commit**

Run `bash tools/run-core-tests.sh`, then:

```bash
git add core/src/main/kotlin/grandlineduo/core/model/WorldState.kt \
        core/src/main/kotlin/grandlineduo/core/persistence/WorldStateCodec.kt \
        core/src/main/kotlin/grandlineduo/core/hash/CanonicalStateHasher.kt \
        core/src/main/kotlin/grandlineduo/game/duel/DuelStateBinaryCodec.kt \
        core/src/main/kotlin/grandlineduo/game/duel/DuelCanonicalState.kt \
        core/src/test/kotlin/grandlineduo/game/duel/DuelPersistenceTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: persist and hash pvp duel state"
```

---

### Task 3: Wire Protocol for Duel Lifecycle

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/core/network/GameplayWireCommand.kt`
- Modify: `core/src/main/kotlin/grandlineduo/core/network/WireCodec.kt`
- Modify: `core/src/main/kotlin/grandlineduo/core/network/Protocol.kt`
- Modify: `core/src/test/kotlin/grandlineduo/core/network/WireCodecTest.kt`

**Interfaces:**
- Produces: `GameplayWireCommand.DuelAction(commandId, actorId, actionType)` with lifecycle actions `CHALLENGE`, `ACCEPT`, `DECLINE`, `CLOSE`.

- [ ] **Step 1: Add failing wire round-trip tests**

Add a test equivalent to:

```kotlin
val command = GameplayWireCommand.DuelAction("duel-cmd", "p2", "ACCEPT")
val decoded = WireCodec.decodeFrame(
    WireCodec.encodeFrame(WireMessage.GameplayCommand(command))
) as WireMessage.GameplayCommand
assertEquals(command, decoded.command)
```

Cover all four lifecycle strings and retain an explicit QuestAction subtype-9 round-trip to protect existing numbering.

- [ ] **Step 2: Run RED**

Run `bash tools/run-core-tests.sh`.

Expected: missing `DuelAction` compile error.

- [ ] **Step 3: Add command + subtype 10 + protocol bump**

In `GameplayWireCommand.kt`:

```kotlin
data class DuelAction(
    override val commandId: String,
    override val actorId: String,
    val actionType: String,
) : GameplayWireCommand {
    override fun fingerprint(): String = "duel-action|$actorId|${actionType.uppercase()}"
}
```

In `WireCodec`, append subtype `10` after QuestAction in both encode/decode branches without altering 1-9.

In `Protocol.kt` change:

```kotlin
const val PROTOCOL_VERSION: Int = 5
```

This forces LAN discovery/handshake mismatch instead of letting a v4 client receive an unknown subtype.

- [ ] **Step 4: Run GREEN and commit**

Run `bash tools/run-core-tests.sh`, then:

```bash
git add core/src/main/kotlin/grandlineduo/core/network/GameplayWireCommand.kt \
        core/src/main/kotlin/grandlineduo/core/network/WireCodec.kt \
        core/src/main/kotlin/grandlineduo/core/network/Protocol.kt \
        core/src/test/kotlin/grandlineduo/core/network/WireCodecTest.kt
git commit -m "feat: add duel lifecycle wire command"
```

---

### Task 4: Host-Authoritative Duel Coordinator

**Files:**
- Create: `core/src/main/kotlin/grandlineduo/game/duel/DuelCoordinator.kt`
- Create: `core/src/test/kotlin/grandlineduo/game/duel/DuelCoordinatorTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: `HostReplica`, snapshot/durable stores, `DuelEngine`, `CombatModifierResolver`, prepared power world/action.
- Produces:

```kotlin
class DuelCoordinator(
    private val hostReplica: HostReplica,
    private val campaignSeed: Long,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
    fun challenge(commandId: String, playerId: String, hostTimestamp: Long): CampaignEvent
    fun accept(commandId: String, playerId: String, hostTimestamp: Long): CampaignEvent
    fun decline(commandId: String, playerId: String, hostTimestamp: Long): CampaignEvent
    fun close(commandId: String, playerId: String, hostTimestamp: Long): CampaignEvent
    fun submitAction(commandId: String, playerId: String, actionType: CombatActionType, hostTimestamp: Long): CampaignEvent
    fun submitPreparedAction(commandId: String, playerId: String, actionType: CombatActionType, preparedWorld: WorldState, sourceFingerprint: String, metadata: MutableMap<String, String>, hostTimestamp: Long): CampaignEvent
}
```

- [ ] **Step 1: Write failing lifecycle/authority tests**

Cover:
- P1->P2 and P2->P1 challenge in `campaign.mode=HOST_COOP`;
- challenge in `campaign.mode=SOLO` rejected with unchanged world;
- challenge requires both P1/P2 profiles and positive HP;
- challenge rejected during `activeCombat`, legacy scenario combat, `activeVoyage`, active non-complete arc, or another duel;
- wrong actor cannot accept/decline;
- ACCEPT copies current `WorldState.players` HP/maxHP exactly and does not heal;
- DECLINE clears pending duel and changes no player/resource state;
- first round action locks only; second resolves once;
- duplicate command id returns the existing event and does not resolve/spend twice;
- ordinary equipment modifiers are used;
- prepared Haki/Devil Fruit action preserves `PowerTechniqueEngine` energy/mastery mutation exactly once;
- finished duel synchronizes HP to world players and never changes party berries, bounty, quest board, or inventory flags;
- CLOSE only works in FINISHED phase and clears duel without HP/energy restoration.

- [ ] **Step 2: Run RED**

Run `bash tools/run-core-tests.sh`.

Expected: missing `DuelCoordinator`.

- [ ] **Step 3: Implement challenge lifecycle**

Generate a stable duel id from campaign + command + challenger:

```kotlin
private fun duelId(world: WorldState, commandId: String, playerId: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("${world.campaignId}|$commandId|$playerId".toByteArray(Charsets.UTF_8))
    return "duel-" + bytes.take(8).joinToString("") { "%02x".format(it) }
}
```

`challenge(...)` validates `world.worldFlags["campaign.mode"] == "HOST_COOP"`, other-player profile exists, both HP > 0, no `activeCombat`, no legacy scenario combat from `StormglassPersistenceAdapter.decode(world).combat`, no `activeVoyage`, no non-complete `activeArc`, and no `activeDuel`. Commit a PENDING `DuelState` with empty fighters.

`accept(...)` only allows `challengedId`, copies both current player HP/maxHP into fighters, sets phase ACTIVE/round 1. `decline(...)` only allows the challenged player and clears the state. `close(...)` allows either participant only when FINISHED and clears it.

- [ ] **Step 4: Implement active rounds and atomic prepared powers**

`submitAction(...)` allows only basic combat actions:

```kotlin
setOf(ATTACK, DEFEND, DODGE, SETUP, FINISHER)
```

Powers must arrive through `submitPreparedAction(...)` so ownership/energy/mastery has already been prepared authoritatively.

For both paths:
1. require valid ACTIVE duel and participant;
2. create `DuelEngine(campaignSeed xor duel.duelId.hashCode().toLong(), CombatModifierResolver.forWorld(world))`;
3. lock action;
4. resolve only if both actions are present;
5. synchronize resolved fighter HP into `WorldState.players` after a resolved round;
6. preserve pending locked state without mutating HP before resolution;
7. commit through `HostReplica.submit(ReplaceWorldStateCommand(...))`;
8. persist event to snapshot/durable store exactly like the existing boss coordinators.

Prepared powers use the passed `preparedWorld` as the base world and commit duel + energy/mastery changes in one event. Event metadata includes `meta.duelId`, `meta.duelPhase`, `meta.duelRound`, `meta.duelResolved`, and terminal `meta.duelFinishReason` when present.

- [ ] **Step 5: Run GREEN and commit**

Run `bash tools/run-core-tests.sh`, then:

```bash
git add core/src/main/kotlin/grandlineduo/game/duel/DuelCoordinator.kt \
        core/src/test/kotlin/grandlineduo/game/duel/DuelCoordinatorTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: add authoritative pvp duel coordinator"
```

---

### Task 5: Gameplay Handler Routing and Exclusivity

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Extend: `core/src/test/kotlin/grandlineduo/game/duel/DuelCoordinatorTest.kt` or create handler-focused cases inside the same test object to keep duel behavior collocated.

**Interfaces:**
- Consumes: `GameplayWireCommand.DuelAction`, `DuelCoordinator`.
- Produces: duel-first routing for lifecycle, `CombatAction`, and `PowerAction` without changing PvE fallback behavior.

- [ ] **Step 1: Add failing handler routing tests**

Test through `StormglassGameplayCommandHandler.handle(...)`:
- `DuelAction(CHALLENGE)` creates pending duel;
- ACCEPT/DECLINE/CLOSE route correctly;
- while `activeDuel.phase == ACTIVE`, ordinary `CombatAction` routes to duel even though `activeCombat == null`;
- while ACTIVE, `PowerAction` runs `PowerTechniqueEngine.prepare` then duel coordinator atomically;
- a world containing both `activeDuel` and `activeCombat` rejects before any resource mutation;
- pending/finished duel rejects `CombatAction`/`PowerAction`;
- while any duel state exists, forged scenario/arc/voyage/inventory/world/quest commands are rejected;
- with `activeDuel == null`, all existing quest-boss/arc/scenario routing remains green.

- [ ] **Step 2: Run RED**

Run `bash tools/run-core-tests.sh`.

Expected: duel commands fall through / are not routed.

- [ ] **Step 3: Add coordinator and exclusive-state guard**

Instantiate one `DuelCoordinator` beside `QuestBossCoordinator`.

Immediately after actor validation, enforce:

```kotlin
if (before.activeDuel != null && command !is GameplayWireCommand.DuelAction) {
    require(before.activeCombat == null) { "Invalid simultaneous duel and PvE combat" }
    require(command is GameplayWireCommand.CombatAction || command is GameplayWireCommand.PowerAction) {
        "Only duel actions are available while a duel is pending or active"
    }
    require(before.activeDuel.phase == DuelPhase.ACTIVE) { "Duel is not active" }
}
```

Handle `DuelAction` before normal world-management branches. Dispatch uppercase `CHALLENGE`, `ACCEPT`, `DECLINE`, `CLOSE`; reject any other lifecycle action.

- [ ] **Step 4: Route combat and powers duel-first**

Before the existing `activeCombat` branch:

```kotlin
if (command is GameplayWireCommand.CombatAction && before.activeDuel?.phase == DuelPhase.ACTIVE) {
    val type = CombatActionType.valueOf(command.actionType)
    require(type in BASIC_COMBAT_ACTIONS) { "Power techniques require a power action" }
    return duelCoordinator.submitAction(command.commandId, command.actorId, type, hostTimestamp)
}
```

At the top of `applyPowerAction`, after simultaneous-state validation but before PvE routing:

```kotlin
val prepared = PowerTechniqueEngine.prepare(before, command.actorId, command.techniqueId)
if (prepared.world.activeDuel?.phase == DuelPhase.ACTIVE) {
    return duelCoordinator.submitPreparedAction(
        command.commandId,
        command.actorId,
        prepared.combatAction,
        prepared.world,
        fingerprint,
        metadata,
        hostTimestamp,
    )
}
```

Then preserve the existing quest boss -> arc boss -> scenario combat paths byte-for-byte wherever possible.

- [ ] **Step 5: Run GREEN and commit**

Run `bash tools/run-core-tests.sh`, then:

```bash
git add core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt \
        core/src/test/kotlin/grandlineduo/game/duel/DuelCoordinatorTest.kt
git commit -m "feat: route gameplay actions through active duels"
```

---

### Task 6: Presenter, Session API, and Android Dispatch

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`
- Modify: `core/src/main/kotlin/grandlineduo/appshell/GameSessionCoordinator.kt`
- Modify: `app/src/main/kotlin/com/grandlineduo/app/MainActivity.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`

**Interfaces:**
- Produces: `GameScreen.DUEL`, lifecycle action kind `DUEL`, `GameSessionCoordinator.submitDuelAction(action: String)`.

- [ ] **Step 1: Write failing presenter/session tests**

Presenter cases:
- HOST_COOP hub with both characters ready exposes `GameAction("CHALLENGE", "Desafiar para duelo", "DUEL")`;
- SOLO hub never exposes it;
- pending challenger sees waiting copy and no actions;
- pending challenged sees exactly ACCEPT and DECLINE lifecycle actions;
- ACTIVE actor with no locked action sees basic COMBAT actions + eligible POWER actions;
- ACTIVE actor who already locked sees `WAITING_FOR_PARTNER` and body only says the opponent is/isn't ready, never the opponent action type;
- FINISHED knockout shows winner/loser and `CLOSE`;
- DOUBLE_KNOCKOUT shows `Empate — nocaute duplo` and `CLOSE`.

Session cases:
- `submitDuelAction("CHALLENGE")` in host mode reaches the gameplay handler;
- SOLO forged challenge is rejected and `autoPlayCompanion()` is not used as a duel response.

- [ ] **Step 2: Run RED**

Run `bash tools/run-core-tests.sh`.

Expected: no `DUEL` screen/API/action kind yet.

- [ ] **Step 3: Implement duel presentation priority**

In `GamePresenter.present(...)`, retain terminal PvE defeat/game-over handling first. Then, before ordinary active combat/voyage/arc presentation:

```kotlin
world.activeDuel?.let { return duelPresentation(world, actorId, it) }
```

Pending presentation:
- challenger: `GameScreen.WAITING_FOR_PARTNER`, title `Desafio enviado`, no actions;
- challenged: `GameScreen.DUEL`, title `Desafio de duelo`, actions ACCEPT/DECLINE with kind `DUEL`.

ACTIVE presentation:
- screen `DUEL`, or `WAITING_FOR_PARTNER` if actor already locked;
- body lists both HP, round, own readiness, and opponent readiness only;
- available basic actions reuse kind `COMBAT`;
- powers reuse kind `POWER` and the existing `PowerTechniqueEngine.available(...)` energy filter.

FINISHED presentation:
- winner/loser or double-KO copy;
- remaining HP;
- one `GameAction("CLOSE", "Encerrar duelo", "DUEL")`.

Only add CHALLENGE in `hub(...)` when `world.worldFlags["campaign.mode"] == "HOST_COOP"`.

- [ ] **Step 4: Add session and Android lifecycle dispatch**

In `GameSessionCoordinator`:

```kotlin
@Synchronized
fun submitDuelAction(action: String): WorldState {
    sendGameplay(
        GameplayWireCommand.DuelAction(
            commandId = nextCommandId("duel"),
            actorId = actorId,
            actionType = action,
        )
    )
    return worldState()
}
```

Do not call `autoPlayCompanion()` from this method.

In `MainActivity.dispatch` add:

```kotlin
"DUEL" -> coordinator.submitDuelAction(action.id)
```

No new Android Activity or custom renderer is required; `GameplayScreen` continues rendering `GamePresentation`.

- [ ] **Step 5: Run GREEN and commit**

Run `bash tools/run-core-tests.sh`, then:

```bash
git add core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt \
        core/src/main/kotlin/grandlineduo/appshell/GameSessionCoordinator.kt \
        app/src/main/kotlin/com/grandlineduo/app/MainActivity.kt \
        core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt \
        core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt
git commit -m "feat: expose consensual pvp duel flow in app shell"
```

---

### Task 7: Real TCP Reconnect and Convergence

**Files:**
- Create: `core/src/test/kotlin/grandlineduo/game/duel/DuelLanIntegrationTest.kt`
- Modify: `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

**Interfaces:**
- Consumes: existing `LanHostServer`, `LanClientConnection`, replicas, gameplay handler.
- Produces: end-to-end evidence that P2 lifecycle/actions and reconnect preserve authoritative duel state.

- [ ] **Step 1: Add the real TCP test and register it**

Use the same real socket pattern as existing LAN gameplay/quest tests. Scenario:

1. Build HOST_COOP world with two complete characters, known HP/energy, known party berries/bounties, no incompatible activity.
2. Start `LanHostServer` with `StormglassGameplayCommandHandler`.
3. Connect P2 `ClientReplica` over TCP.
4. P2 sends `DuelAction(CHALLENGE)`; refresh and assert host/client PENDING convergence.
5. Host/P1 accepts through the same handler; P2 refreshes and both see ACTIVE round 1 with exact pre-duel HP.
6. P1 submits `CombatAction(SETUP)` while P2 remains connected; verify only P1 action is locked.
7. Disconnect P2.
8. Recover/recreate a fresh P2 `ClientReplica` from its persisted/known last confirmed state and reconnect.
9. Refresh and assert the locked P1 action, round, fighters, and canonical hash match host.
10. P2 submits `CombatAction(FINISHER)`; continue deterministic rounds until FINISHED.
11. Assert host/client canonical hashes converge and loser is 1 HP.
12. Assert party berries, bounties, quest board, and inventory-related world flags equal their pre-duel values; energy differs only by legitimately used power actions if the test uses one.
13. Send `DuelAction(CLOSE)` and assert `activeDuel == null` on host/client without healing.
14. Re-send the exact same CLOSE command id and assert no second mutation/event effect.

- [ ] **Step 2: Run RED if integration exposes a gap, otherwise record immediate GREEN**

Run `bash tools/run-core-tests.sh`.

Expected: the new end-to-end test must pass. If it fails, use `superpowers:systematic-debugging` before changing production code; add the smallest production fix and a focused regression assertion.

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/grandlineduo/game/duel/DuelLanIntegrationTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "test: cover pvp duel reconnect over real tcp"
```

---

### Task 8: Full Regression and Android Verification

**Files:**
- No planned production files unless verification finds a real defect.
- Update: `docs/superpowers/plans/2026-08-21-consensual-pvp-duel.md` only with actual verification results after tests/build complete.

**Interfaces:**
- Produces: evidence the exact branch head is green in core and Android and existing PvE behavior is intact.

- [ ] **Step 1: Run the full core suite on the exact source head**

```bash
bash tools/run-core-tests.sh
```

Expected: `RESULT N/N passed` with zero failures. Explicitly inspect output for:
- all duel tests;
- quest boss tests;
- arc boss tests;
- power combat tests;
- quest LAN tests;
- existing LAN transport/reconnect tests;
- campaign/session presenter tests.

- [ ] **Step 2: Build Android from the same source head**

```bash
gradle --no-daemon --stacktrace :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and non-empty debug APK.

- [ ] **Step 3: Record APK hash**

```bash
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Record the exact SHA-256 in the verification section of this plan and PR #4 description if this dependent work remains on that PR.

- [ ] **Step 4: Verify no accidental PvE protocol/state regressions**

Review the final diff and confirm:
- `CombatEngine.kt` was not modified;
- gameplay subtypes 1-9 were not renumbered;
- snapshot v1-10 decode branches remain intact;
- null-duel hash compatibility test passes;
- no duel rewards or automatic healing were introduced;
- SOLO presenter does not show duel challenge;
- quest boss `START_BOSS` flow and source binding remain unchanged.

- [ ] **Step 5: Commit verification notes only if they add durable evidence**

```bash
git add docs/superpowers/plans/2026-08-21-consensual-pvp-duel.md
git commit -m "docs: record pvp duel verification"
```

If no documentation text needs changing, do not create an empty verification commit.
