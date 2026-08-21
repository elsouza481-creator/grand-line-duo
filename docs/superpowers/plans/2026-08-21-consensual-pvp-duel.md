# Consensual PvP Duel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic, host-authoritative, reconnect-safe, non-lethal PvP duel between the two human GRAND LINE DUO players without changing the existing PvE combat engine.

**Architecture:** Introduce a separate `grandlineduo.game.duel` subsystem with persistent `DuelState`, pure `DuelEngine`, host-side `DuelCoordinator`, a dedicated `DuelAction` wire lifecycle command, and presenter/session integration. Ordinary duel rounds reuse `CombatAction` and `PowerAction`, but `StormglassGameplayCommandHandler` routes them to the duel coordinator whenever `activeDuel` is ACTIVE; PvE boss/scenario combat remains source-separated.

**Tech Stack:** Kotlin/JVM core, Android Kotlin app shell, custom deterministic test registry, binary `WorldStateCodec`, SHA-256 canonical hashing, existing `HostReplica`/`ClientReplica`/real TCP LAN transport, Gradle Android build.

**Spec:** `docs/superpowers/specs/2026-08-21-consensual-pvp-duel-design.md`

## Global Constraints

- PvP is consensual and available only in two-human `HOST_COOP` campaigns; forged SOLO challenges are rejected by host authority.
- The first PvP slice is non-lethal: a knockout ends at 1 HP, including both fighters at 1 HP for `DOUBLE_KNOCKOUT`.
- Entering, finishing, or closing a duel never heals HP, refunds energy, or grants berries, bounty, loot, reputation, quest progress, or quest rewards.
- Existing `CombatEngine` remains PvE-only; do not add PvP branches to it.
- Reuse `CombatActionType`, `CombatModifiers`, `CombatModifierResolver`, and `PowerTechniqueEngine.prepare(...)`.
- Opponent locked-action type is never exposed by `GamePresenter` before resolution; only readiness may be shown.
- Duel state is structured persistent state, not a `worldFlags` encoding.
- Existing snapshot versions remain readable; a world with `activeDuel == null` retains the previous canonical hash representation.
- Gameplay wire subtypes 1-9 are not renumbered. Add DuelAction as subtype 10 and bump `PROTOCOL_VERSION` from 4 to 5 so old clients cannot silently join a duel-capable session.
- A duel challenge is valid only from a hub-compatible state: both characters exist and have positive HP; no active PvE combat, legacy scenario combat, voyage, non-complete arc, or other duel; the restored scenario must be COMPLETE when no completed arc supplies the hub state.
- While any duel exists, only DuelAction lifecycle commands are legal; CombatAction/PowerAction are additionally legal only in ACTIVE phase. Other gameplay commands are rejected instead of racing the duel state.
- Production changes are TDD-first with frequent independently green commits.
- Final verification requires the full core suite and `gradle --no-daemon --stacktrace :app:assembleDebug`.

---

## File Structure

**Create**
- `core/src/main/kotlin/grandlineduo/game/duel/DuelState.kt` — duel domain model/enums.
- `core/src/main/kotlin/grandlineduo/game/duel/DuelEngine.kt` — pure deterministic simultaneous round resolution.
- `core/src/main/kotlin/grandlineduo/game/duel/DuelStateBinaryCodec.kt` — binary snapshot codec.
- `core/src/main/kotlin/grandlineduo/game/duel/DuelCanonicalState.kt` — deterministic canonical hash block.
- `core/src/main/kotlin/grandlineduo/game/duel/DuelCoordinator.kt` — host authority and lifecycle.
- `core/src/test/kotlin/grandlineduo/game/duel/DuelEngineTest.kt`
- `core/src/test/kotlin/grandlineduo/game/duel/DuelCoordinatorTest.kt`
- `core/src/test/kotlin/grandlineduo/game/duel/DuelPersistenceTest.kt`
- `core/src/test/kotlin/grandlineduo/game/duel/DuelLanIntegrationTest.kt`

**Modify**
- `core/src/main/kotlin/grandlineduo/core/model/WorldState.kt`
- `core/src/main/kotlin/grandlineduo/core/persistence/WorldStateCodec.kt`
- `core/src/main/kotlin/grandlineduo/core/hash/CanonicalStateHasher.kt`
- `core/src/main/kotlin/grandlineduo/core/network/GameplayWireCommand.kt`
- `core/src/main/kotlin/grandlineduo/core/network/WireCodec.kt`
- `core/src/main/kotlin/grandlineduo/core/network/Protocol.kt`
- `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- `core/src/main/kotlin/grandlineduo/appshell/GamePresenter.kt`
- `core/src/main/kotlin/grandlineduo/appshell/GameSessionCoordinator.kt`
- `app/src/main/kotlin/com/grandlineduo/app/MainActivity.kt`
- `core/src/test/kotlin/grandlineduo/core/network/WireCodecTest.kt`
- `core/src/test/kotlin/grandlineduo/appshell/GamePresenterTest.kt`
- `core/src/test/kotlin/grandlineduo/appshell/GameSessionCoordinatorTest.kt`
- `core/src/test/kotlin/grandlineduo/test/TestRunner.kt`

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

```kotlin
enum class DuelPhase { PENDING, ACTIVE, FINISHED }
enum class DuelFinishReason { KNOCKOUT, DOUBLE_KNOCKOUT }
data class DuelFighter(val id: String, val name: String, val hp: Int, val maxHp: Int)
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
data class DuelRoundResult(val state: DuelState, val damageTaken: Map<String, Int>, val log: List<String>)
class DuelEngine(seed: Long, modifiers: Map<String, CombatModifiers> = emptyMap())
```

- [ ] **Step 1: Write failing engine tests and register them**

Create `DuelEngineTest` as an `object` with `fun register()` following the repository test pattern. The deterministic-order test starts with:

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
val a = DuelEngine(99L).resolveIfReady(base.copy(lockedActions = mapOf(
    "p1" to CombatAction("p1", CombatActionType.ATTACK),
    "p2" to CombatAction("p2", CombatActionType.DEFEND),
)))!!
val b = DuelEngine(99L).resolveIfReady(base.copy(lockedActions = mapOf(
    "p2" to CombatAction("p2", CombatActionType.DEFEND),
    "p1" to CombatAction("p1", CombatActionType.ATTACK),
)))!!
assertEquals(a, b)
```

Also assert exactly:
- duplicate same-round action is rejected;
- DEFEND takes less damage than no defense on the same seed;
- a selected deterministic DODGE seed fully avoids a direct attack;
- KENBUNSHOKU succeeds on a seed where ordinary DODGE fails, demonstrating the higher threshold;
- SETUP creates `setupReady` only for its actor on the next round;
- setup-backed FINISHER adds exactly +12 pre-defense damage;
- attackBonus and damageReduction affect only their owner;
- Busoshoku/Haoshoku/Devil Fruit matching modifier bonuses apply;
- one projected KO => FINISHED/KNOCKOUT, loser HP 1, correct ids;
- two projected KOs => FINISHED/DOUBLE_KNOCKOUT, both HP 1, null winner/loser;
- duel state contains no enemy, telegraph, or co-op combo concept.

Register `grandlineduo.game.duel.DuelEngineTest.register()` in `TestRunner.main()`.

- [ ] **Step 2: Run RED**

```bash
bash tools/run-core-tests.sh
```

Expected: compile failure because duel domain types do not exist.

- [ ] **Step 3: Implement `DuelState.kt`**

Use the exact interfaces above plus:

```kotlin
class DuelRuleException(message: String) : RuntimeException(message)
```

Validate actions in the engine, not constructors, so persistence can decode and validate state centrally.

- [ ] **Step 4: Implement `DuelEngine.kt` with fighter-specific deterministic RNG**

```kotlin
private fun rng(round: Int, playerId: String, salt: Long): Random =
    Random(seed xor (round.toLong() * -7046029254386353131L) xor
        (playerId.hashCode().toLong() * 6364136223846793005L) xor salt)
```

Exact base damage before setup/attack bonus:

```text
ATTACK          14 + nextInt(5)
DEFEND           0
DODGE            0
SETUP            4 + nextInt(3)
FINISHER        14 + nextInt(6)
BUSOSHOKU       18 + nextInt(6) + busoshokuBonus
KENBUNSHOKU      0
HAOSHOKU        16 + nextInt(7) + haoshokuBonus
DEVIL_FRUIT     16 + nextInt(7) + devilFruitBonus
```

Apply `attackBonus` to ATTACK, SETUP, FINISHER, BUSOSHOKU, HAOSHOKU, DEVIL_FRUIT. If attacker is in previous `setupReady`, add +12 to FINISHER, +6 to another offensive action. Previous setup expires after this resolution; next `setupReady` is exactly current SETUP actors.

Defense before `damageReduction`:

```text
DEFEND: retain 35% incoming, minimum 1 when incoming > 0.
DODGE: 65% deterministic avoidance vs ATTACK/SETUP/FINISHER/BUSOSHOKU/DEVIL_FRUIT; cannot avoid HAOSHOKU.
KENBUNSHOKU: 85% avoidance against the same direct set, 50% against HAOSHOKU; failed Kenbunshoku retains 50% incoming.
Otherwise: full incoming.
```

Subtract defender damageReduction afterward, floor 0. Compute both projected HP from pre-round state and apply simultaneously. Double projected KO => both 1 HP + DOUBLE_KNOCKOUT. Single projected KO => loser 1 HP, winner retains projected HP minimum 1. Otherwise increment round and clear locked actions.

- [ ] **Step 5: Run GREEN**

```bash
bash tools/run-core-tests.sh
```

Expected: all old tests + DuelEngineTest pass.

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

**Interfaces:** `WorldState.activeDuel: DuelState?`, snapshot v11, conditional duel canonical block.

- [ ] **Step 1: Write failing persistence/hash tests**

Round-trip PENDING, ACTIVE with one locked action + setupReady, FINISHED/KNOCKOUT, FINISHED/DOUBLE_KNOCKOUT.

Use this exact v10 minimal snapshot fixture; it decodes to campaign `legacy-v10`, event 7, island `origin`, 123 berries, empty quest/player/flag state, and must produce `activeDuel == null`:

```kotlin
val legacyV10 = Base64.getDecoder().decode(
    "AAAACgAKbGVnYWN5LXYxMAAAAAAAAAAHAAZvcmlnaW4AAAAAAAAAewAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
)
val decoded = WorldStateCodec.decode(legacyV10)
assertEquals("legacy-v10", decoded.campaignId)
assertEquals(7L, decoded.lastEventId)
assertEquals(123L, decoded.partyBerries)
assertEquals(null, decoded.activeDuel)
```

Hash tests:

```kotlin
assertEquals(CanonicalStateHasher.hash(world), CanonicalStateHasher.hash(world.copy(activeDuel = null)))
assertNotEquals(CanonicalStateHasher.hash(world), CanonicalStateHasher.hash(world.copy(activeDuel = pending)))
```

Create logically identical ACTIVE duels with reverse insertion order for fighters/locked actions and assert equal hashes.

Register `DuelPersistenceTest`.

- [ ] **Step 2: Run RED**

```bash
bash tools/run-core-tests.sh
```

Expected: missing `activeDuel`/codec symbols.

- [ ] **Step 3: Add `activeDuel` and snapshot v11**

In `WorldState.kt`, adjacent to activeCombat:

```kotlin
val activeDuel: DuelState? = null,
```

In `WorldStateCodec` set `CURRENT_VERSION = 11`. After quest-board bytes and before players, write presence boolean + `DuelStateBinaryCodec.write`. During decode read only when `version >= 11`; versions 1-10 get null. Pass into reconstructed world.

`DuelStateBinaryCodec` writes enum names and sorted maps/sets. Enforce participant ids p1/p2, at most two fighters/actions/setup ids, no duplicate ids, non-negative HP, `hp <= maxHp`, PENDING has no fighters/actions, ACTIVE has exactly two fighters and no terminal fields, FINISHED has a finishReason and no locked actions; KNOCKOUT requires non-null winner/loser, DOUBLE_KNOCKOUT requires both null.

- [ ] **Step 4: Add conditional canonical encoding**

`DuelCanonicalState.encode` writes version, duel id, challenger/challenged, phase, round, sorted fighters, sorted locked actions, sorted setupReady, winner/loser empty when null, finishReason empty when null.

`CanonicalStateHasher` only appends:

```kotlin
state.activeDuel?.let { append(DuelCanonicalState.encode(it)) }
```

No duel marker exists for null state.

- [ ] **Step 5: Run GREEN and commit**

```bash
bash tools/run-core-tests.sh
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

**Produces:**

```kotlin
data class DuelAction(
    override val commandId: String,
    override val actorId: String,
    val actionType: String,
) : GameplayWireCommand {
    override fun fingerprint(): String = "duel-action|$actorId|${actionType.uppercase()}"
}
```

- [ ] **Step 1: Write failing round-trip tests**

For CHALLENGE, ACCEPT, DECLINE, CLOSE, encode `WireMessage.GameplayCommand(DuelAction(...))`, decode, assert equality. Keep an explicit QuestAction subtype-9 round-trip regression.

- [ ] **Step 2: Run RED**

```bash
bash tools/run-core-tests.sh
```

- [ ] **Step 3: Add subtype 10 and protocol 5**

Append gameplay subtype 10 in both WireCodec encode/decode branches; do not alter 1-9. Change:

```kotlin
const val PROTOCOL_VERSION: Int = 5
```

- [ ] **Step 4: Run GREEN and commit**

```bash
bash tools/run-core-tests.sh
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

**Produces:**

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
    fun submitPreparedAction(
        commandId: String,
        playerId: String,
        actionType: CombatActionType,
        preparedWorld: WorldState,
        sourceFingerprint: String,
        metadata: MutableMap<String, String>,
        hostTimestamp: Long,
    ): CampaignEvent
}
```

- [ ] **Step 1: Write failing coordinator tests**

Cover P1->P2 and P2->P1 challenge; SOLO rejection; missing profile/zero HP rejection; wrong actor accept/decline; exact HP copy on ACCEPT; challenge rejection with activeCombat, legacy scenario combat, activeVoyage, non-complete arc, story-stage scenario not complete, another duel; DECLINE no resource changes; first action locks/second resolves; duplicate command id idempotency; ordinary equipment modifiers; prepared power energy/mastery exactly once; KO world HP sync with no reward-state changes; CLOSE only after FINISHED and no healing/refund.

Register `DuelCoordinatorTest`.

- [ ] **Step 2: Run RED**

```bash
bash tools/run-core-tests.sh
```

- [ ] **Step 3: Implement lifecycle validation and commits**

Stable duel id:

```kotlin
private fun duelId(world: WorldState, commandId: String, playerId: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("${world.campaignId}|$commandId|$playerId".toByteArray(Charsets.UTF_8))
    return "duel-" + bytes.take(8).joinToString("") { "%02x".format(it) }
}
```

Challenge validation:

```kotlin
val restored = StormglassPersistenceAdapter.decode(world)
require(world.worldFlags["campaign.mode"] == "HOST_COOP")
require(world.players["p1"]?.profile != null && world.players["p2"]?.profile != null)
require((world.players["p1"]?.hp ?: 0) > 0 && (world.players["p2"]?.hp ?: 0) > 0)
require(world.activeCombat == null && restored.combat == null)
require(world.activeVoyage == null)
require(world.activeDuel == null)
require(world.activeArc == null || world.activeArc.phase == ArcPhase.COMPLETE)
require(restored.scenario.stage == ScenarioStage.COMPLETE || world.activeArc?.phase == ArcPhase.COMPLETE)
```

CHALLENGE creates PENDING with empty fighters. ACCEPT only challenged actor: copy both current HP/maxHP, phase ACTIVE, round 1. DECLINE only challenged actor: clear. CLOSE either participant only in FINISHED: clear.

Persist every accepted command through HostReplica and snapshot/durable path, with source fingerprint matching command lifecycle semantics and metadata `meta.duelId`, `meta.duelPhase`.

- [ ] **Step 4: Implement active rounds and prepared powers**

Ordinary `submitAction` allows only ATTACK/DEFEND/DODGE/SETUP/FINISHER. Power categories only use `submitPreparedAction`.

Engine creation:

```kotlin
DuelEngine(
    campaignSeed xor duel.duelId.hashCode().toLong(),
    CombatModifierResolver.forWorld(baseWorld),
)
```

Lock, resolve only when both actions exist, synchronize HP to WorldState.players after a resolved round, and preserve locked state without HP mutation before resolution. Prepared powers use `preparedWorld` as base so energy/mastery + duel state commit atomically. Metadata includes duel id/phase/round/resolved and terminal finish reason.

- [ ] **Step 5: Run GREEN and commit**

```bash
bash tools/run-core-tests.sh
git add core/src/main/kotlin/grandlineduo/game/duel/DuelCoordinator.kt \
        core/src/test/kotlin/grandlineduo/game/duel/DuelCoordinatorTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "feat: add authoritative pvp duel coordinator"
```

---

### Task 5: Gameplay Handler Routing and Duel Exclusivity

**Files:**
- Modify: `core/src/main/kotlin/grandlineduo/game/network/StormglassGameplayCommandHandler.kt`
- Modify: `core/src/test/kotlin/grandlineduo/game/duel/DuelCoordinatorTest.kt`

- [ ] **Step 1: Add failing handler tests**

Through `StormglassGameplayCommandHandler.handle`: DuelAction lifecycle routes correctly; ACTIVE CombatAction routes to duel even with no activeCombat; ACTIVE PowerAction prepares then atomically routes; activeDuel + activeCombat rejects before resource mutation; PENDING/FINISHED reject CombatAction/PowerAction; any duel rejects scenario/arc/voyage/inventory/world/quest commands; null duel preserves existing quest-boss/arc/scenario routing.

- [ ] **Step 2: Run RED**

```bash
bash tools/run-core-tests.sh
```

- [ ] **Step 3: Instantiate DuelCoordinator and enforce exclusive state**

After actor validation:

```kotlin
if (before.activeDuel != null && command !is GameplayWireCommand.DuelAction) {
    require(before.activeCombat == null) { "Invalid simultaneous duel and PvE combat" }
    require(command is GameplayWireCommand.CombatAction || command is GameplayWireCommand.PowerAction) {
        "Only duel actions are available while a duel exists"
    }
    require(before.activeDuel.phase == DuelPhase.ACTIVE) { "Duel is not active" }
}
```

Dispatch uppercase CHALLENGE/ACCEPT/DECLINE/CLOSE to the coordinator before normal management branches.

- [ ] **Step 4: Route CombatAction and PowerAction duel-first**

CombatAction ACTIVE duel branch occurs before activeCombat PvE routing and requires a basic action.

In `applyPowerAction`, first reject simultaneous duel+PvE state, call `PowerTechniqueEngine.prepare`, then if prepared world has ACTIVE duel call `duelCoordinator.submitPreparedAction(...)`. Otherwise preserve quest boss -> arc boss -> scenario power flow.

- [ ] **Step 5: Run GREEN and commit**

```bash
bash tools/run-core-tests.sh
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

**Produces:** `GameScreen.DUEL`, lifecycle action kind `DUEL`, `GameSessionCoordinator.submitDuelAction(action: String)`.

- [ ] **Step 1: Write failing presenter/session tests**

Presenter:
- HOST_COOP hub exposes `GameAction("CHALLENGE", "Desafiar para duelo", "DUEL")`;
- SOLO hub does not;
- pending challenger sees waiting/no actions;
- pending challenged sees ACCEPT + DECLINE only;
- ACTIVE unlocked actor sees basic COMBAT + eligible POWER actions;
- ACTIVE locked actor sees WAITING_FOR_PARTNER and opponent readiness only, never action type;
- FINISHED KO shows winner/loser + CLOSE;
- DOUBLE_KNOCKOUT shows `Empate — nocaute duplo` + CLOSE.

Session: host `submitDuelAction("CHALLENGE")` reaches handler; SOLO challenge rejects and companion AI never responds as PvP opponent.

- [ ] **Step 2: Run RED**

```bash
bash tools/run-core-tests.sh
```

- [ ] **Step 3: Implement presentation**

Retain terminal PvE defeat first. Then before normal active combat/voyage/arc:

```kotlin
world.activeDuel?.let { return duelPresentation(world, actorId, it) }
```

Pending challenger => WAITING_FOR_PARTNER. Pending challenged => DUEL with ACCEPT/DECLINE kind DUEL. ACTIVE => DUEL or WAITING if actor locked; body shows both HP/round/readiness; basic actions kind COMBAT; powers kind POWER using existing availability/energy filter. FINISHED => outcome + remaining HP + `GameAction("CLOSE", "Encerrar duelo", "DUEL")`.

Hub adds CHALLENGE only when campaign.mode is HOST_COOP.

- [ ] **Step 4: Add session/Android lifecycle dispatch**

```kotlin
@Synchronized
fun submitDuelAction(action: String): WorldState {
    sendGameplay(GameplayWireCommand.DuelAction(nextCommandId("duel"), actorId, action))
    return worldState()
}
```

Do not call `autoPlayCompanion()`.

MainActivity dispatch:

```kotlin
"DUEL" -> coordinator.submitDuelAction(action.id)
```

Reuse existing `GameplayScreen`; no new Activity/view architecture.

- [ ] **Step 5: Run GREEN and commit**

```bash
bash tools/run-core-tests.sh
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

- [ ] **Step 1: Add and register one real TCP lifecycle test**

Use existing LAN test socket pattern and execute exactly:
1. HOST_COOP world with two complete characters, known HP/energy/berries/bounties, no incompatible state.
2. Start LanHostServer + StormglassGameplayCommandHandler.
3. Connect P2 ClientReplica.
4. P2 CHALLENGE; refresh and assert PENDING host/client convergence.
5. P1 ACCEPT; P2 refresh; assert ACTIVE round 1 and exact original HP.
6. P1 CombatAction SETUP; assert only P1 locked.
7. Disconnect P2.
8. Recreate a fresh P2 ClientReplica from its last confirmed state and reconnect.
9. Refresh; assert same locked P1 action, round, fighters, canonical hash.
10. P2 FINISHER; continue deterministic ATTACK/FINISHER rounds until FINISHED.
11. Assert host/client canonical hashes converge and loser is 1 HP.
12. Assert berries, bounties, quest board, inventory-related flags unchanged from pre-duel values.
13. CLOSE; assert null duel on host/client with no healing.
14. Resend exact same CLOSE command id; assert idempotent no second mutation.

- [ ] **Step 2: Run the full suite**

```bash
bash tools/run-core-tests.sh
```

Expected: the new TCP test passes. If it fails, invoke `superpowers:systematic-debugging`, identify root cause, add the smallest focused regression assertion, then fix.

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/grandlineduo/game/duel/DuelLanIntegrationTest.kt \
        core/src/test/kotlin/grandlineduo/test/TestRunner.kt
git commit -m "test: cover pvp duel reconnect over real tcp"
```

---

### Task 8: Full Regression and Android Verification

**Files:**
- Modify plan only if recording concrete verification output.
- Update PR #4 description with final duel verification evidence.

- [ ] **Step 1: Full core suite on exact head**

```bash
bash tools/run-core-tests.sh
```

Expected `RESULT N/N passed`, zero failures. Explicitly inspect duel, quest boss, arc boss, powers, quest LAN, LAN transport/reconnect, campaign/session/presenter cases.

- [ ] **Step 2: Android build on the same source head**

```bash
gradle --no-daemon --stacktrace :app:assembleDebug
```

Expected `BUILD SUCCESSFUL` and non-empty `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Record APK hash**

```bash
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Add exact SHA-256 and exact core test count to the plan verification notes and PR #4 description.

- [ ] **Step 4: Final diff invariants**

Confirm:
- `CombatEngine.kt` unchanged;
- wire subtypes 1-9 unchanged;
- snapshot v1-10 decode paths intact;
- null-duel legacy hash test green;
- no duel reward/auto-heal;
- SOLO hides challenge;
- quest boss START_BOSS/source binding unchanged.

- [ ] **Step 5: Commit verification notes only when actual evidence is added**

```bash
git add docs/superpowers/plans/2026-08-21-consensual-pvp-duel.md
git commit -m "docs: record pvp duel verification"
```
