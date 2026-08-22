# Automatic Quest Objectives + Real HUNT Combat Design

**Date:** 2026-08-22

## Goal

Replace manual progress for HUNT contracts with real deterministic PvE encounters, while introducing a reusable quest-objective event foundation that later supports EXPLORE, COLLECT, RESCUE, ESCORT, and INVESTIGATE without coupling `QuestEngine` directly to every gameplay subsystem.

This first slice deliberately automates **HUNT only**. Other non-BOSS quest types keep their current manual progress path until their corresponding authoritative world events exist, so no accepted contract becomes impossible to complete during migration.

## Non-goals

This slice does not:

- remove manual `PROGRESS` from EXPLORE, COLLECT, RESCUE, ESCORT, or INVESTIGATE;
- change BOSS contract behavior or `QuestBossCoordinator`;
- change the existing PvE `CombatEngine` formulas;
- create a second combat engine for HUNT;
- add a new snapshot version;
- add a new gameplay wire subtype;
- grant quest rewards automatically on objective completion;
- heal HP or energy when starting, winning, losing, or closing a HUNT encounter;
- add ranked/matchmaking PvP or alter the duel subsystem.

## Current Problem

`QuestEngine.progress(...)` currently lets every non-BOSS contract advance through a generic manual command, and `GamePresenter.presentQuests(...)` exposes a `Registrar progresso` button for those contracts.

That is mechanically functional but does not create adventure gameplay. HUNT already carries a semantic `targetId` such as `dock-raiders`, so it can be upgraded to objective-driven progression without changing `QuestDefinition` or the quest-board persistence format.

## Architecture

Add two focused pieces under `grandlineduo.game.quest`:

1. `QuestObjectiveRouter` — pure quest-domain adapter that translates authoritative world events into quest progress.
2. `QuestHuntCoordinator` + `QuestHuntFactory` — host-authoritative HUNT encounter lifecycle built on the existing PvE `CombatEngine`.

The handler remains the orchestration boundary. Gameplay systems emit or construct typed objective events only after an authoritative outcome exists. The router never observes UI actions and never infers progress from a command merely being attempted.

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

Rules:

- `targetId` and `islandId` must be non-blank.
- `amount` must be positive.
- `sourceQuestId`, when present, must be non-blank.
- The event itself is transient and is **not persisted** in `WorldState`.
- Persistence comes from the resulting quest-board mutation inside the same authoritative command commit.
- Events are created only from successfully resolved host-authoritative outcomes.
- A retried command id cannot apply the event twice because existing `HostReplica` command idempotency returns the original committed event/state.

`QuestObjectiveRouter.apply(world, event)` scans active quests in deterministic quest-id order and applies progress only when all matching rules pass.

For this first slice, only `ENEMY_DEFEATED` is active:

```text
quest.status == ACTIVE
quest.type == HUNT
quest.definition.islandId == event.islandId
quest.definition.targetId == event.targetId
event.type == ENEMY_DEFEATED
sourceQuestId == null OR quest.definition.questId == sourceQuestId
```

Matching HUNT quests receive `QuestEngine.progress(world, questId, event.amount)`.

HUNT encounters created by `QuestHuntCoordinator` **always set `sourceQuestId` to the bound HUNT quest id**. This prevents defeating one contract-specific `dock-raiders` encounter from accidentally advancing another active contract that happens to use the same `targetId`.

A future ambient/world ENEMY_DEFEATED integration may intentionally omit `sourceQuestId` so all semantically matching active contracts can react, but that behavior is outside this slice and must get its own tests before use.

The router must not advance BOSS quests, READY_TO_TURN_IN quests, failed/completed quests, quests on another island, source-quest mismatches, or targets with only partial/string-similar ids.

Future event types are reserved by the foundation but must be no-ops until their dedicated gameplay integration is implemented and tested.

## HUNT Encounter Lifecycle

A HUNT contract remains accepted through the existing `ACCEPT` path.

For an ACTIVE HUNT contract:

1. Player selects `START_HUNT` (`Rastrear e enfrentar alvo`).
2. `QuestHuntCoordinator.start(...)` validates the contract and that the world is currently in a hub-compatible state, then creates one deterministic encounter.
3. Existing P1/P2 combat actions and power actions resolve through the existing `CombatEngine`.
4. On VICTORY, the coordinator creates one `ENEMY_DEFEATED` objective event for the contract target with `sourceQuestId = questId`.
5. `QuestObjectiveRouter` applies the rarity-scaled amount atomically in the same authoritative world-state commit.
6. If progress is still below the requirement, combat clears and the HUNT remains ACTIVE; UI exposes `START_HUNT` again as `Continuar caçada`.
7. When progress reaches the required amount, status becomes `READY_TO_TURN_IN`; rewards still require explicit existing `TURN_IN`.
8. On DEFEAT, the HUNT permanently fails with reason metadata `HUNT_DEFEAT`; no reward is granted.

Manual `PROGRESS` for HUNT is rejected at the domain/handler boundary once this feature is active.

## Hub-Compatible Start Rule

`START_HUNT` is host-validated even if a modified client forges the command outside the quest screen.

A HUNT may start only when all of these are true:

- both P1/P2 player records exist and both characters have completed profiles;
- both players have positive HP;
- `activeCombat == null`;
- legacy scenario combat is null;
- `activeVoyage == null`;
- `activeDuel == null`;
- no HUNT/BOSS combat binding exists;
- if `activeArc != null`, its phase is `COMPLETE`;
- if there is no active arc, the restored scenario stage is `COMPLETE`.

This mirrors what the official presenter treats as a hub instead of trusting UI reachability as an authorization rule.

## Three-Encounter Progression Model

The current procedural HUNT archetype has base required amount `3`, multiplied by rarity:

```text
COMMON     required 3
RARE       required 6
EPIC       required 9
LEGENDARY  required 12
```

Each successful HUNT encounter contributes:

```text
COMMON     +1
RARE       +2
EPIC       +3
LEGENDARY  +4
```

Therefore a normal generated HUNT takes exactly **three successful encounters** regardless of rarity.

This prevents legendary HUNT contracts from becoming twelve repetitive battles while preserving the existing rarity-scaled required amount and quest-board representation.

The router clamps through existing `QuestEngine.progress(...)`, so a victory can never exceed `requiredAmount`.

## Deterministic HUNT Combat Factory

`QuestHuntFactory.create(world, questProgress, campaignSeed)` is pure and deterministic.

Validation:

- quest type is HUNT;
- quest status is ACTIVE;
- quest island matches current `world.islandId`;
- both P1/P2 player records exist;
- no HUNT encounter starts from a completed/failed/ready quest.

Player combatants copy current `hp` and `maxHp` exactly. No healing occurs.

Enemy balance by rarity:

```text
COMMON      48 HP /  9 ATK
RARE        72 HP / 12 ATK
EPIC       100 HP / 15 ATK
LEGENDARY  135 HP / 18 ATK
```

The encounter ordinal is derived from current progress and per-win amount:

```kotlin
val encounterIndex = questProgress.progress /
    progressPerVictory(questProgress.definition.rarity) + 1
```

For generated quests this produces indexes 1, 2, and 3.

Stable enemy identity:

```text
<targetId>-hunt-<encounterIndex>
```

Examples:

```text
dock-raiders-hunt-1
dock-raiders-hunt-2
dock-raiders-hunt-3
```

The objective event still uses the base quest `targetId` (`dock-raiders`), not the per-wave enemy combat id.

Deterministic combat seed is derived only from stable authoritative inputs:

```text
campaignSeed
questId
targetId
rarity
encounterIndex
```

No wall-clock value, map insertion order, client-local state, or command timestamp participates in combat generation.

Initial telegraph selection uses this deterministic seed and the same telegraph/action model already used by PvE combat.

## Persistent Origin Binding

Reuse the proven quest-boss ownership pattern without changing snapshot schema.

World flag:

```text
quest.hunt.active = <questId>
```

Rules:

- the binding exists only while a HUNT `activeCombat` is running;
- `start` rejects if `activeCombat` or `activeVoyage` already exists;
- start rejects if another HUNT or BOSS binding already exists;
- a HUNT binding with no valid active HUNT quest is considered invalid and commands reject instead of falling back to arc/scenario combat;
- VICTORY clears `activeCombat` and the binding while applying the objective event in the same committed state;
- DEFEAT keeps the terminal defeat combat state consistent with existing hardcore PvE behavior, removes the HUNT binding, and moves the quest to failed history;
- no snapshot version bump is required because `worldFlags` is already persisted and hashed.

Constant:

```kotlin
QuestHuntCoordinator.ACTIVE_QUEST_FLAG = "quest.hunt.active"
```

## Coordinator Responsibilities

`QuestHuntCoordinator` owns only HUNT combat lifecycle:

```kotlin
class QuestHuntCoordinator(
    hostReplica: HostReplica,
    campaignSeed: Long,
    snapshotStore: SnapshotStore? = null,
    durableStore: DurableCampaignStore? = null,
)
```

Public operations:

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

- validate HUNT start, hub compatibility, and origin binding;
- create deterministic HUNT combat;
- route ordinary combat actions through existing `CombatEngine`;
- route prepared Haki/Akuma no Mi actions through existing `PowerTechniqueEngine.prepare(...)` contract;
- use `CombatModifierResolver.forWorld(...)` so equipment and powers behave exactly like other PvE combat;
- atomically persist lock/resolution, energy/mastery state, HP synchronization, quest progress/failure, and metadata;
- preserve command-id idempotency and durable crash recovery.

It does **not** own quest rewards, inventory acquisition logic, Director generation, Android rendering, or future objective event sources.

## Combat Routing

`StormglassGameplayCommandHandler` source-routing order must remain explicit.

### Quest lifecycle

`GameplayWireCommand.QuestAction` gains action string `START_HUNT` using existing subtype 9. No wire subtype or protocol-version change is required.

- `START_BOSS` -> existing `QuestBossCoordinator.start`.
- `START_HUNT` -> new `QuestHuntCoordinator.start`.
- other quest actions -> existing quest-management path.

### Basic CombatAction

When `activeCombat != null`:

1. reject immediately if both `quest.hunt.active` and `quest.boss.active` exist;
2. if `quest.hunt.active` exists -> `QuestHuntCoordinator.submitAction`;
3. else if `quest.boss.active` exists -> `QuestBossCoordinator.submitAction`;
4. else -> existing arc/scenario route.

### PowerAction

Continue to call `PowerTechniqueEngine.prepare(...)` first, preserving the existing duel check before PvE combat source routing.

After prepare:

1. active duel -> existing `DuelCoordinator` path;
2. reject if both HUNT and BOSS bindings exist;
3. HUNT binding + active combat -> `QuestHuntCoordinator.submitPreparedAction(...)`;
4. BOSS binding + active combat -> existing `QuestBossCoordinator.submitPreparedAction(...)`;
5. otherwise -> existing arc/scenario power route.

For HUNT, energy cost and mastery/use counters are committed exactly once with the combat action; duplicate/retried command ids cannot double-consume energy.

## HP, Energy, Rewards, and Hardcore Consequences

- Starting a HUNT copies current HP exactly.
- No HP is restored between HUNT encounters.
- Energy is not restored by HUNT lifecycle.
- Power energy spent stays spent.
- After every resolved combat round, `WorldState.players` HP synchronizes with combat fighters.
- VICTORY grants objective progress only; no Berries, PEV, item, faction standing, bounty, loot, or healing is granted at that point.
- Existing `TURN_IN` remains the only reward grant point.
- DEFEAT follows hardcore semantics: the quest permanently fails and the terminal combat consequence is not silently healed/reset.

## Solo Behavior

SOLO uses the existing companion combat planner exactly as quest BOSS and arc PvE already do.

Rules:

- no new companion AI is created;
- when P1 starts a HUNT encounter in SOLO, existing companion behavior supplies P2 actions through the normal authoritative command path;
- the companion cannot bypass energy, inventory, combat, or death rules;
- HUNT combat remains capable of party defeat.

## Android / Presenter Behavior

For ACTIVE HUNT:

- remove `PROGRESS|<questId>|1` action;
- when no HUNT combat is active, expose:

```text
START_HUNT|<questId>|1
```

Labels:

- progress `0`: `Rastrear e enfrentar alvo • <title>`
- progress `> 0` and still ACTIVE: `Continuar caçada • <title>`

While HUNT combat is active, normal combat presentation takes priority and reuses existing COMBAT/POWER controls.

After the final victory, HUNT becomes READY_TO_TURN_IN and exposes only existing `TURN_IN`.

Other non-BOSS quest types continue to show manual `Registrar progresso` in this slice.

Android `MainActivity` needs no new dispatcher kind because `START_HUNT` already travels through generic `QUEST` parsing.

## Metadata

HUNT commits include useful deterministic metadata without making metadata authoritative state.

Start:

```text
meta.questAction=START_HUNT
meta.questId=<questId>
meta.huntEncounter=<index>
meta.huntTarget=<targetId>
```

Round resolution follows existing combat metadata plus:

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

## Failure and Invalid-State Rules

Reject without mutation when:

- `START_HUNT` targets a non-HUNT quest;
- quest is not ACTIVE;
- quest belongs to another island;
- either character profile is incomplete or either player has no positive HP;
- command is forged outside the hub-compatible state defined above;
- active PvE combat already exists;
- voyage or duel is active;
- another HUNT or BOSS binding exists;
- HUNT binding references a missing/non-HUNT/inactive quest;
- both HUNT and BOSS bindings exist;
- manual `PROGRESS` targets HUNT;
- a player submits a second action in the same round;
- a power is unavailable, locked, suppressed, or lacks energy;
- combat action is submitted after terminal HUNT combat;
- a retry reuses a command id with different fingerprint.

## Persistence and Compatibility

No new structured state is necessary.

Existing persisted pieces are sufficient:

- quest progress lives in `QuestBoardState` already persisted in snapshot v10+;
- active combat lives in structured `activeCombat` already persisted;
- origin binding lives in persisted/hashed `worldFlags`;
- current snapshot remains v11 because PvP already introduced that version;
- v1-v10 compatibility paths remain unchanged;
- no canonical-hash compatibility marker changes are required beyond ordinary changed quest/combat/worldFlag data.

Crash recovery must reproduce the same locked HUNT round, origin binding, HP, energy/mastery state, and quest progress from snapshot + durable event tail.

## TDD / Verification Requirements

### 1. QuestObjectiveRouterTest

Cover:

- matching ENEMY_DEFEATED increments the bound HUNT;
- same target on a different active quest does **not** progress when `sourceQuestId` points to one contract;
- wrong sourceQuestId does nothing;
- wrong target does nothing;
- wrong island does nothing;
- non-HUNT does nothing;
- READY_TO_TURN_IN does not advance;
- amount clamps at required amount;
- future event types are no-op in this slice;
- deterministic ordering for the future `sourceQuestId == null` multi-match path.

### 2. QuestHuntFactoryTest

Cover:

- deterministic identical inputs;
- exact rarity HP/ATK tiers;
- exact current player HP copied without healing;
- encounter index derived from progress;
- stable per-encounter enemy id;
- different encounter index changes deterministic combat seed/telegraph when seed permits.

### 3. QuestHuntCoordinatorTest

Cover:

- valid HUNT starts combat + binding from hub;
- forged start outside hub rejects;
- non-HUNT rejects;
- wrong island/status rejects;
- incomplete/dead character rejects;
- incompatible combat/voyage/duel rejects;
- ordinary action locks/resolves;
- equipment modifiers apply;
- prepared Haki/Akuma power spends energy/use exactly once;
- victory clears combat/binding and increments exact rarity amount only on the bound contract;
- first/second victory remains ACTIVE;
- third generated victory becomes READY_TO_TURN_IN;
- no reward on victory;
- defeat fails quest permanently with no reward;
- command retry idempotent;
- invalid/missing binding rejects instead of falling back.

### 4. Handler/Presenter/Session Tests

Cover:

- `START_HUNT` routes to HUNT coordinator;
- HUNT-bound CombatAction routes before quest boss/arc;
- HUNT-bound PowerAction is atomic;
- simultaneous HUNT+BOSS binding rejects;
- Presenter removes manual HUNT PROGRESS;
- first encounter label is `Rastrear e enfrentar alvo`;
- later ACTIVE label is `Continuar caçada`;
- final status exposes TURN_IN;
- other non-BOSS types retain manual progress for migration compatibility;
- SOLO companion resolves P2 action through existing planner.

### 5. Real TCP Lifecycle

One full integration test must execute:

1. HOST_COOP in a hub-compatible state with active HUNT and known HP/energy/rewards.
2. P2 starts or participates in HUNT over real TCP.
3. P1 locks an action.
4. P2 disconnects before round resolution.
5. Fresh client replica starts from its last persisted snapshot.
6. Reconnect restores combat, binding, locks, HP, quest progress, and canonical hash.
7. Fight resolves to victory.
8. Host/client converge and exact rarity progress is applied once to the bound quest only.
9. Repeat encounters until READY_TO_TURN_IN.
10. TURN_IN grants reward exactly once.
11. Duplicate command retry does not duplicate progress, energy use, or reward.

### 6. Full Regression

Final verification requires:

```bash
bash tools/run-core-tests.sh
gradle --no-daemon --stacktrace :app:assembleDebug
```

Also explicitly confirm:

- existing quest BOSS start/victory/defeat tests remain green;
- `CombatEngine.kt` remains unchanged;
- PvP duel tests remain green;
- protocol version stays 5 and existing wire subtype assignments remain unchanged;
- snapshot v11 + v1-v10 compatibility tests remain green;
- no temporary Android verification workflow remains in final PR diff.

## Migration Sequence After This Slice

This design intentionally creates the reusable router first, then migrates quest types in separate reviewed slices:

1. **This slice:** HUNT -> `ENEMY_DEFEATED`.
2. **Next:** EXPLORE -> `ISLAND_VISITED` / exploration-location events; COLLECT -> authoritative `ITEM_ACQUIRED` from real loot/acquisition sources.
3. **Later:** RESCUE -> `NPC_RESCUED`; ESCORT -> `ESCORT_ARRIVED`; INVESTIGATE -> `CLUE_DISCOVERED`.
4. Remove generic manual `PROGRESS` only after every generated non-BOSS quest type has a real authoritative completion source.

This staged migration keeps the game playable at every commit and avoids accepting contracts that cannot advance.