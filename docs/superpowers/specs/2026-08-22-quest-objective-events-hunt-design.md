# Automatic Quest Objectives + Real HUNT Combat Design

**Date:** 2026-08-22

## Goal

Replace manual progress for HUNT contracts with real deterministic PvE encounters, while introducing a reusable quest-objective event foundation that later supports EXPLORE, COLLECT, RESCUE, ESCORT, and INVESTIGATE without coupling `QuestEngine` directly to every gameplay subsystem.

This first slice deliberately automates **HUNT only**. Other non-BOSS quest types keep their current manual progress path until their corresponding authoritative world events exist, so no accepted contract becomes impossible to complete during migration.

## Non-goals

This slice does not:

- remove manual `PROGRESS` from EXPLORE, COLLECT, RESCUE, ESCORT, or INVESTIGATE;
- change BOSS contract behavior or `QuestBossCoordinator`;
- change existing PvE `CombatEngine` formulas;
- create a second combat engine for HUNT;
- add a new snapshot version or gameplay wire subtype;
- grant quest rewards automatically on objective completion;
- heal HP or energy when starting, winning, losing, or closing a HUNT encounter;
- alter PvP duel behavior.

## Current Problem

`QuestEngine.progress(...)` currently lets every non-BOSS contract advance through a generic manual command, and `GamePresenter.presentQuests(...)` exposes a `Registrar progresso` button.

HUNT already carries a semantic `targetId` such as `dock-raiders`, so it can move to objective-driven progression without changing `QuestDefinition` or quest-board persistence.

## Architecture

Add three focused pieces under `grandlineduo.game.quest`:

1. `QuestObjectiveRouter` — pure adapter from authoritative world events to quest progress.
2. `QuestHuntFactory` — pure deterministic construction of one HUNT PvE encounter.
3. `QuestHuntCoordinator` — host-authoritative HUNT combat lifecycle using the existing `CombatEngine`.

The gameplay handler remains the orchestration boundary. Objective events are created only after an authoritative outcome exists. The router never observes UI actions and never infers progress from a command merely being attempted.

## Manual Progress vs Objective Progress

The migration must not create a backdoor where the same API used by a manual button can also advance an automated HUNT.

`QuestEngine` therefore separates the two paths:

```kotlin
fun progress(world: WorldState, questId: String, amount: Int): WorldState
fun progressObjective(world: WorldState, questId: String, amount: Int): WorldState
```

Rules:

- `progress(...)` remains the manual path and rejects both `QuestType.BOSS` and `QuestType.HUNT`.
- In this slice `progressObjective(...)` accepts only ACTIVE HUNT contracts.
- Both methods validate positive amount and clamp to `requiredAmount` through one private shared advancement function.
- `progressObjective(...)` is never exposed as a wire command or presenter action.
- Future automated quest types are explicitly added to `progressObjective(...)` only when their real event source is implemented.

This makes manual HUNT progression impossible while still giving the objective router a narrow domain API.

## Quest Objective Event Foundation

Introduce:

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
```

Invariants:

- `targetId` and `islandId` are non-blank.
- `amount > 0`.
- `sourceQuestId`, when present, is non-blank.
- The event is transient and is not persisted in `WorldState`.
- Persistence is the resulting quest-board mutation committed atomically with the authoritative gameplay outcome.
- Retry idempotency is inherited from the original host command id; the objective event is not a second independently submitted command.

`QuestObjectiveRouter.apply(world, event)` scans active quests in deterministic quest-id order.

Only `ENEMY_DEFEATED` is active in this slice. A quest matches when:

```text
status == ACTIVE
type == HUNT
quest.islandId == event.islandId
quest.targetId == event.targetId
event.type == ENEMY_DEFEATED
sourceQuestId == null OR quest.questId == sourceQuestId
```

A match calls:

```kotlin
QuestEngine.progressObjective(world, questId, event.amount)
```

HUNT encounters created by `QuestHuntCoordinator` always set `sourceQuestId` to the bound quest id. One contract-specific `dock-raiders` victory therefore cannot accidentally advance another active contract with the same target.

A future ambient-world ENEMY_DEFEATED source may intentionally omit `sourceQuestId`, but that behavior is outside this slice and must receive dedicated tests before use.

Future event types are defined now for the common interface but are no-ops until their own integration slice.

## HUNT Encounter Lifecycle

A HUNT is accepted through existing `ACCEPT`.

For an ACTIVE HUNT:

1. Player selects `START_HUNT` (`Rastrear e enfrentar alvo`).
2. `QuestHuntCoordinator.start(...)` validates quest + hub compatibility and creates one deterministic encounter.
3. P1/P2 ordinary combat actions and powers use existing PvE `CombatEngine` behavior.
4. VICTORY creates `ENEMY_DEFEATED(targetId, islandId, rarityAmount, sourceQuestId=questId)`.
5. `QuestObjectiveRouter` advances only the bound contract atomically in the same authoritative commit.
6. If incomplete, combat clears, binding clears, HUNT stays ACTIVE, and UI exposes `Continuar caçada`.
7. When objective progress reaches the requirement, status becomes `READY_TO_TURN_IN`; reward still requires existing explicit `TURN_IN`.
8. DEFEAT permanently fails the HUNT with metadata `HUNT_DEFEAT`; no reward is granted.

## Hub-Compatible Start Rule

`START_HUNT` is host-validated so a modified client cannot forge a hunt from story/combat state.

All must hold:

- P1/P2 records exist and both profiles are complete;
- both players have positive HP;
- `activeCombat == null`;
- restored legacy scenario combat is null;
- `activeVoyage == null`;
- `activeDuel == null`;
- neither HUNT nor BOSS origin binding exists;
- if `activeArc != null`, its phase is `COMPLETE`;
- if there is no active arc, restored scenario stage is `COMPLETE`.

This mirrors the official presenter's definition of a hub rather than trusting UI reachability as authorization.

## Three-Encounter Progression Model

Current procedural HUNT required amounts are:

```text
COMMON      3
RARE        6
EPIC        9
LEGENDARY  12
```

Each successful encounter contributes:

```text
COMMON     +1
RARE       +2
EPIC       +3
LEGENDARY  +4
```

Therefore a generated HUNT takes exactly **three successful encounters** at every rarity.

`progressObjective(...)` clamps the final amount to `requiredAmount`.

## Deterministic HUNT Combat Factory

`QuestHuntFactory.create(world, questProgress, campaignSeed)` is pure.

Validation:

- type HUNT;
- status ACTIVE;
- quest island equals current island;
- both P1/P2 exist;
- no ready/completed/failed hunt is accepted as an encounter source.

Players copy current `hp` / `maxHp` exactly. No healing occurs.

Enemy tiers:

```text
COMMON      48 HP /  9 ATK
RARE        72 HP / 12 ATK
EPIC       100 HP / 15 ATK
LEGENDARY  135 HP / 18 ATK
```

Encounter index:

```kotlin
val encounterIndex = questProgress.progress /
    progressPerVictory(questProgress.definition.rarity) + 1
```

Generated hunts therefore use indexes 1, 2, 3.

Stable combat enemy id:

```text
<targetId>-hunt-<encounterIndex>
```

The objective event uses the base `targetId`, not this per-encounter combat id.

Combat seed depends only on stable authoritative inputs:

```text
campaignSeed
questId
targetId
rarity
encounterIndex
```

No timestamp, client-local state, map insertion order, or wall clock participates.

Initial telegraph uses the existing PvE telegraph model and this deterministic seed.

## Persistent HUNT Origin Binding

Use the proven quest-boss binding pattern:

```text
quest.hunt.active = <questId>
```

Constant:

```kotlin
QuestHuntCoordinator.ACTIVE_QUEST_FLAG = "quest.hunt.active"
```

Rules:

- binding exists only while a HUNT `activeCombat` is running;
- start rejects existing PvE combat, voyage, duel, HUNT binding, or BOSS binding;
- a HUNT binding referencing a missing/non-HUNT/inactive quest is invalid and rejects commands rather than falling back to another combat source;
- if HUNT and BOSS bindings coexist, reject as invalid state;
- VICTORY atomically clears combat + binding and applies objective progress;
- DEFEAT keeps the terminal defeat combat state, removes binding, and moves quest to failed history;
- no snapshot version bump: `worldFlags`, `activeCombat`, and quest progress are already persisted/hashed.

## QuestHuntCoordinator

Constructor:

```kotlin
class QuestHuntCoordinator(
    hostReplica: HostReplica,
    campaignSeed: Long,
    snapshotStore: SnapshotStore? = null,
    durableStore: DurableCampaignStore? = null,
)
```

Operations:

```kotlin
start(commandId, playerId, questId, hostTimestamp)
submitAction(commandId, playerId, actionType, hostTimestamp)
submitPreparedAction(
    commandId,
    playerId,
    actionType,
    preparedWorld,
    sourceFingerprint,
    metadata,
    hostTimestamp,
)
```

Responsibilities:

- validate HUNT start, hub state, and binding;
- build encounter with `QuestHuntFactory`;
- resolve ordinary actions with existing `CombatEngine`;
- use `CombatModifierResolver.forWorld(...)` for equipment and power modifiers;
- accept prepared Haki/Akuma actions after `PowerTechniqueEngine.prepare(...)`;
- synchronize player HP after resolved rounds;
- on victory create the bound `QuestObjectiveEvent` and route it;
- on defeat fail the quest;
- atomically persist locks, resolution, HP, energy/mastery, progress/failure, and metadata;
- preserve command-id idempotency and durable recovery.

It does not grant turn-in rewards, own inventory acquisition, generate Director quests, or render Android UI.

## Handler Routing

### QuestAction

No new wire subtype or protocol version is needed. Existing `QuestAction` subtype 9 gains string action `START_HUNT`.

- `START_BOSS` -> existing boss coordinator.
- `START_HUNT` -> new hunt coordinator.
- manual `PROGRESS` -> existing quest path, where HUNT is rejected by `QuestEngine.progress(...)`.

### Basic CombatAction

When structured `activeCombat != null`:

1. reject if HUNT and BOSS bindings both exist;
2. HUNT binding -> `QuestHuntCoordinator.submitAction`;
3. BOSS binding -> existing `QuestBossCoordinator.submitAction`;
4. otherwise preserve existing arc/scenario routing.

### PowerAction

Keep existing prepare-first behavior and duel priority.

After `PowerTechniqueEngine.prepare(...)`:

1. active duel -> existing duel coordinator;
2. reject simultaneous HUNT+BOSS binding;
3. HUNT binding + combat -> hunt coordinator prepared action;
4. BOSS binding + combat -> existing boss coordinator prepared action;
5. otherwise existing arc/scenario power route.

Energy/mastery state and HUNT action commit atomically. Retried command ids cannot double-spend energy.

## HP, Energy, Rewards, Hardcore Consequences

- No HP restoration at HUNT start or between encounters.
- No HUNT lifecycle energy restoration.
- Power energy spent remains spent.
- Resolved round HP synchronizes back to `WorldState.players`.
- Victory gives objective progress only: no Berries, PEV, item, faction, bounty, loot, healing, or refund.
- Existing `TURN_IN` remains the only reward grant point.
- DEFEAT permanently fails the contract and does not silently heal/reset terminal consequences.

## Solo Behavior

SOLO reuses the existing companion combat planner.

- no new companion AI;
- P1 starts the HUNT normally;
- existing planner supplies P2 combat actions through the authoritative path;
- companion cannot bypass inventory, energy, combat, or death rules;
- party defeat remains possible.

## Presenter / Android

For ACTIVE HUNT:

- no `PROGRESS|<questId>|1` action;
- if no HUNT combat is active, expose `START_HUNT|<questId>|1`.

Labels:

```text
progress == 0: Rastrear e enfrentar alvo • <title>
progress > 0:  Continuar caçada • <title>
```

During combat, normal COMBAT/POWER presentation takes priority.

After final victory, READY_TO_TURN_IN exposes only existing TURN_IN.

EXPLORE/COLLECT/RESCUE/ESCORT/INVESTIGATE retain `Registrar progresso` during this migration slice.

`MainActivity` requires no new action kind because generic QUEST parsing already carries `START_HUNT`.

## Metadata

Start:

```text
meta.questAction=START_HUNT
meta.questId=<questId>
meta.huntEncounter=<index>
meta.huntTarget=<targetId>
```

Round:

```text
meta.huntQuestId=<questId>
meta.huntEncounter=<index>
```

Victory:

```text
meta.questObjective=ENEMY_DEFEATED
meta.questObjectiveSourceQuest=<questId>
meta.questObjectiveTarget=<targetId>
meta.questObjectiveAmount=<rarity amount>
meta.questProgress=<new progress>
```

Defeat:

```text
meta.questFailure=HUNT_DEFEAT
```

Metadata remains descriptive, not authoritative state.

## Invalid-State Rules

Reject without mutation when:

- START_HUNT targets non-HUNT, wrong island, or non-ACTIVE quest;
- either profile incomplete or player HP non-positive;
- start is forged outside hub-compatible state;
- PvE combat, voyage, duel, HUNT binding, or BOSS binding already exists;
- HUNT binding points to missing/non-HUNT/inactive quest;
- HUNT+BOSS bindings coexist;
- manual PROGRESS targets HUNT;
- duplicate same-round fighter action;
- unavailable/locked/suppressed/insufficient-energy power;
- action after terminal HUNT combat;
- command id is reused with a different fingerprint.

## Persistence / Compatibility

No structured schema change is needed.

- quest progress already persists in `QuestBoardState`;
- combat already persists in `activeCombat`;
- origin binding persists in `worldFlags`;
- snapshot remains v11;
- v1-v10 decode paths remain unchanged;
- protocol remains v5;
- existing wire subtype assignments remain unchanged;
- canonical hash changes naturally only when quest/combat/worldFlag authoritative state changes.

Crash recovery must reproduce locked HUNT action, origin binding, HP, energy/mastery, and quest progress from snapshot + durable event tail.

## TDD Requirements

### QuestEngine / QuestObjectiveRouter

Tests must prove:

- manual `progress(...)` rejects HUNT and BOSS but still supports the five migration types;
- `progressObjective(...)` accepts ACTIVE HUNT only;
- matching bound ENEMY_DEFEATED increments only that HUNT;
- same target on another active HUNT does not progress with `sourceQuestId` set;
- wrong source quest / target / island does nothing;
- non-HUNT and READY quests do not advance;
- amount clamps to requirement;
- future event types are no-op;
- future `sourceQuestId == null` multi-match order is deterministic.

### QuestHuntFactoryTest

- deterministic identical inputs;
- exact rarity HP/ATK tiers;
- exact current player HP, no healing;
- encounter index from progress;
- stable per-encounter enemy id;
- encounter index participates in seed/telegraph.

### QuestHuntCoordinatorTest

- valid hub start creates combat + binding;
- forged non-hub start rejects;
- type/island/status/profile/HP validation;
- combat/voyage/duel/binding conflicts reject;
- first lock / second action resolves;
- equipment modifiers apply;
- prepared Haki/Akuma spends energy/use exactly once;
- victory clears combat/binding and increments exact rarity amount on bound contract only;
- first/second generated victory stays ACTIVE;
- third generated victory becomes READY_TO_TURN_IN;
- no reward on victory;
- defeat permanently fails without reward;
- command retry idempotent;
- invalid binding never falls through to arc/boss.

### Handler / Presenter / Session

- START_HUNT routes to hunt coordinator;
- hunt-bound CombatAction route wins over boss/arc source paths;
- hunt-bound PowerAction is atomic;
- simultaneous HUNT+BOSS binding rejects;
- Presenter removes HUNT manual progress;
- first/later labels are correct;
- final state exposes TURN_IN;
- other migration quest types retain manual progress;
- SOLO existing companion resolves P2 action.

### Real TCP Lifecycle

One test must execute:

1. HOST_COOP in hub state with active HUNT and known HP/energy/rewards.
2. P2 starts or participates over real TCP.
3. P1 locks an action.
4. P2 disconnects before resolution.
5. Fresh P2 replica starts from stale persisted snapshot.
6. Reconnect restores combat, binding, locked action, HP, quest progress, canonical hash.
7. Resolve victory.
8. Host/client converge and rarity progress applies exactly once to bound quest.
9. Repeat encounters until READY_TO_TURN_IN.
10. TURN_IN grants reward exactly once.
11. Duplicate command retry cannot duplicate progress, energy use, or reward.

## Final Verification

Required:

```bash
bash tools/run-core-tests.sh
gradle --no-daemon --stacktrace :app:assembleDebug
```

Explicit invariants:

- existing quest BOSS tests stay green;
- `CombatEngine.kt` stays unchanged;
- PvP duel tests stay green;
- protocol stays v5 and wire subtype assignments stay unchanged;
- snapshot v11 plus v1-v10 compatibility stays green;
- no temporary Android verification workflow remains in final PR diff.

## Migration Sequence After This Slice

1. **This slice:** HUNT -> ENEMY_DEFEATED.
2. **Next:** EXPLORE -> exploration/location events; COLLECT -> authoritative ITEM_ACQUIRED from real acquisition/loot.
3. **Later:** RESCUE -> NPC_RESCUED; ESCORT -> ESCORT_ARRIVED; INVESTIGATE -> CLUE_DISCOVERED.
4. Remove generic manual PROGRESS only after every generated non-BOSS quest has a real authoritative completion source.

The staged migration keeps every generated contract completable at every commit.