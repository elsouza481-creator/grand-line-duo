# EXPLORE + COLLECT Field Objectives Design

**Date:** 2026-08-24

**Status:** Proposed written specification after approved in-chat design

**Branch:** `feature/quest-contract-system`

**Context:** HUNT already progresses through authoritative `QuestObjectiveEvent` events. EXPLORE and COLLECT still use manual `PROGRESS`. This slice converts only EXPLORE and COLLECT to real deterministic field checks while preserving existing HUNT, BOSS, PvP, persistence, LAN and Android behavior.

## 1. Goals

1. Remove manual `PROGRESS` from EXPLORE and COLLECT contracts.
2. Give EXPLORE and COLLECT real D20 gameplay with resource cost and visible results.
3. Reuse `QuestObjectiveRouter` instead of creating a second quest progression path.
4. Make every field attempt host-authoritative, deterministic, command-id idempotent, durable and reconnect-safe.
5. Preserve existing snapshot version 11, network protocol version 5 and QuestAction wire subtype 9.
6. Keep RESCUE, ESCORT and INVESTIGATE on manual `PROGRESS` until their real world-event sources are implemented.
7. Keep rewards exclusively behind existing explicit `TURN_IN`.

## 2. Non-goals

This slice does not build an island tile map, POI graph, loot-node system, random encounter map, new Android screen, new wire command subtype or new snapshot block. It does not make shop purchases count as collection, does not make campaign island arrival count as exploration, and does not automate RESCUE, ESCORT or INVESTIGATE.

`medical-supplies` remains a quest objective identity, not a new inventory catalog item. `forgotten-ruins` remains a quest location identity, not a new persistent map entity.

## 3. Architecture

Add two focused quest-domain units under `grandlineduo.game.quest`:

### 3.1 `QuestFieldResolver`

Pure deterministic rules for:

- validating the field action against quest type;
- difficulty class by rarity;
- ability/skill modifier selection;
- deterministic D20 generation;
- progress-per-success by rarity;
- producing a `QuestFieldAttemptResult` without mutating world state.

It has no persistence, network, host replica or UI dependency.

### 3.2 `QuestFieldCoordinator`

Host-authoritative boundary for one atomic EXPLORE/COLLECT field attempt. It owns:

- command-id/fingerprint idempotency;
- actor/quest/hub validation;
- reading and incrementing the persistent attempt ordinal;
- spending exactly 1 PE;
- invoking `QuestFieldResolver`;
- recording the visible last-check state in `worldFlags`;
- emitting a bound `QuestObjectiveEvent` on success;
- applying that event through `QuestObjectiveRouter` in the same authoritative commit;
- durable event/snapshot persistence.

There is no long-lived `activeFieldAction` state. One field action is one atomic authoritative command.

### 3.3 Existing orchestration boundary

`StormglassGameplayCommandHandler` instantiates `QuestFieldCoordinator` and routes `QuestAction.actionType` values:

- `EXPLORE_SITE`
- `SEARCH_SUPPLIES`

before ordinary quest management.

No new `GameplayWireCommand` subtype is added.

## 4. Objective Event Contract

Extend the transient enum with one new event type:

```kotlin
enum class QuestObjectiveEventType {
    ENEMY_DEFEATED,
    ISLAND_VISITED,
    LOCATION_VISITED,
    ITEM_ACQUIRED,
    NPC_RESCUED,
    ESCORT_ARRIVED,
    CLUE_DISCOVERED,
}
```

`ISLAND_VISITED` remains reserved and is still a no-op in this slice. `LOCATION_VISITED` is the event used by EXPLORE field checks.

Router mapping becomes:

```text
ENEMY_DEFEATED   -> ACTIVE HUNT
LOCATION_VISITED -> ACTIVE EXPLORE
ITEM_ACQUIRED    -> ACTIVE COLLECT
```

The existing exact-match rules remain mandatory:

- quest status is `ACTIVE`;
- quest island equals event island;
- quest target equals event target;
- event type maps to the exact quest type;
- `sourceQuestId == null || sourceQuestId == questId`.

Field coordinator events always set `sourceQuestId` to the bound quest id. Therefore one `forgotten-ruins` or `medical-supplies` contract can never progress another same-target contract.

Ambient future world events may omit `sourceQuestId`; that behavior remains deterministic by sorted quest id and is outside this slice.

## 5. QuestEngine Progress Authorization

`QuestEngine.progress(...)` remains the manual migration API but now rejects four automated types:

- BOSS
- HUNT
- EXPLORE
- COLLECT

It continues to support only:

- RESCUE
- ESCORT
- INVESTIGATE

`QuestEngine.progressObjective(...)` accepts only `ACTIVE` quests whose type is one of:

- HUNT
- EXPLORE
- COLLECT

It is still an internal authoritative objective API, never a presenter action and never a wire action.

The shared private clamp/ACTIVE-to-READY transition remains unchanged.

## 6. Field Action Types

```kotlin
enum class QuestFieldActionType {
    EXPLORE_SITE,
    SEARCH_SUPPLIES,
}
```

Exact authorization:

- `EXPLORE_SITE` requires an ACTIVE EXPLORE quest.
- `SEARCH_SUPPLIES` requires an ACTIVE COLLECT quest.
- mismatched action/quest type rejects without mutation.
- `QuestAction.amount` must equal `1`; clients cannot forge objective quantity through the existing amount field.

## 7. D20 Resolution

### 7.1 Difficulty class

```text
COMMON     CD 10
RARE       CD 12
EPIC       CD 15
LEGENDARY  CD 18
```

### 7.2 EXPLORE modifier

EXPLORE uses:

```text
PER + max(PERCEPTION, SURVIVAL, INVESTIGATION)
```

Skill ranks absent from the profile map count as zero.

When skill ranks tie, deterministic display/check identity priority is:

1. PERCEPTION
2. SURVIVAL
3. INVESTIGATION

The modifier is still the same numeric maximum; the priority only determines the recorded check label.

### 7.3 COLLECT modifier

COLLECT chooses the numerically best of:

```text
PER + SURVIVAL
INT + MEDICINE
INT + INVESTIGATION
```

Tie priority is exactly the order above.

### 7.4 Success

```text
total = d20 + modifier
success = total >= difficultyClass
```

Natural 1 and natural 20 have no additional automatic rule in this slice. The raw D20 remains visible in the result.

### 7.5 Deterministic roll

The attempt ordinal is quest-global, persistent and starts at 1. It increments for every accepted field attempt, success or failure.

The D20 seed uses only stable authoritative inputs:

```text
campaignSeed
questId
targetId
quest rarity
field action type
actorId
attempt ordinal
```

No wall clock, client local state, map iteration order or command id participates in the roll.

A suitable implementation is a stable XOR/multiplication composition followed by `java.util.Random(seed).nextInt(20) + 1`. Exact constants are implementation details but must be frozen by deterministic tests.

Command retry does not advance the ordinal and returns the original event/result.

## 8. Resource Rules

Every accepted field attempt costs exactly **1 PE**, whether it succeeds or fails.

Validation happens before spending:

- actor exists and is P1 or P2;
- both P1/P2 profiles are complete;
- both P1/P2 have positive HP;
- actor has at least 1 PE;
- quest exists, is ACTIVE, is on current island and matches the action type;
- field action is allowed in current hub-compatible state;
- no incompatible active gameplay state exists.

After validation, the coordinator subtracts 1 from the acting player's energy before applying the result. Energy cannot go below zero.

There is:

- no free HP restoration;
- no energy restoration;
- no energy refund on failure;
- no Berries/PEV/item/faction/bounty/loot reward for a successful field check;
- no permanent quest failure from a failed field check.

At 0 PE, further field attempts reject. Existing consumables/shop systems remain the recovery route.

## 9. Hub-Compatible State Validation

A forged field action must reject unless all of the following are true:

- structured `activeCombat == null`;
- restored legacy scenario combat is null;
- `activeVoyage == null`;
- `activeDuel == null`;
- no HUNT origin binding;
- no BOSS origin binding;
- if `activeArc != null`, its phase is `COMPLETE`;
- if `activeArc == null`, restored scenario stage is `COMPLETE`.

This intentionally matches the safe between-adventures window used for quest combat start. Field checks do not execute concurrently with story decisions, arc scenes, combat, voyage or duel state.

## 10. Progress Per Successful Check

The generated Director requirements stay unchanged.

### EXPLORE

Existing required amount is `3 * rarity multiplier`.

```text
COMMON      +1 / 3
RARE        +2 / 6
EPIC        +3 / 9
LEGENDARY   +4 / 12
```

Therefore every generated EXPLORE requires exactly **3 successful discoveries**.

### COLLECT

Existing required amount is `4 * rarity multiplier`.

```text
COMMON      +1 / 4
RARE        +2 / 8
EPIC        +3 / 12
LEGENDARY   +4 / 16
```

Therefore every generated COLLECT requires exactly **4 successful searches**.

Progress clamps at required amount and the final success changes status to `READY_TO_TURN_IN`.

## 11. Persistent Field Attempt State

No structured snapshot model is added. State is stored in existing canonical `worldFlags`.

For each active EXPLORE/COLLECT quest:

```text
quest.field.attempt.<questId>        = integer accepted-attempt count
quest.field.last.actor.<questId>     = p1 | p2
quest.field.last.action.<questId>    = EXPLORE_SITE | SEARCH_SUPPLIES
quest.field.last.check.<questId>     = stable human-readable check id
quest.field.last.roll.<questId>      = 1..20
quest.field.last.modifier.<questId>  = signed integer
quest.field.last.total.<questId>     = signed integer
quest.field.last.dc.<questId>        = 10 | 12 | 15 | 18
quest.field.last.success.<questId>   = true | false
```

These flags intentionally participate in the existing canonical hash because they affect future deterministic rolls and visible authoritative state.

The attempt count is quest-global, not per actor. Switching actors does not reset the roll stream.

On `TURN_IN` or `FAIL`, all `quest.field.*.<questId>` keys are removed so long-running campaigns do not accumulate resolved-contract field state. Cleanup is deterministic and does not affect HUNT/BOSS or migration quest types.

## 12. `QuestFieldResolver` API

Recommended pure API:

```kotlin
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

object QuestFieldResolver {
    fun resolve(
        world: WorldState,
        progress: QuestProgress,
        actorId: String,
        actionType: QuestFieldActionType,
        attemptOrdinal: Int,
        campaignSeed: Long,
    ): QuestFieldAttemptResult

    fun difficultyClass(rarity: QuestRarity): Int
    fun progressPerSuccess(rarity: QuestRarity): Int
}
```

`progressAmount` is the rarity multiplier 1/2/3/4. It is only applied when `success == true`.

## 13. `QuestFieldCoordinator` API

Recommended API:

```kotlin
class QuestFieldCoordinator(
    private val hostReplica: HostReplica,
    private val campaignSeed: Long,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) {
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

```text
quest-field|<playerId>|<actionType>|<questId>
```

A duplicate command id with the same fingerprint returns the original event without spending PE, incrementing attempt count or progressing twice. Reuse with a different fingerprint rejects as a command collision.

## 14. Atomic Mutation Order

For a new valid attempt:

1. Read authoritative `before` world.
2. Validate actor, quest, type, island, energy and hub state.
3. Read `attemptOrdinal = existingAttemptCount + 1`.
4. Resolve deterministic D20 from `before`.
5. Create `preparedWorld` with exactly 1 PE spent and all field-result flags updated.
6. If failure: commit `preparedWorld` only.
7. If success: create a bound objective event with `sourceQuestId = questId` and apply `QuestObjectiveRouter` to `preparedWorld`.
8. Commit exactly one `ReplaceWorldStateCommand` / campaign event.
9. Persist through durable store or snapshot store exactly like existing coordinators.

There must never be a state where PE is durably spent but the same accepted attempt's objective success is missing, or vice versa.

## 15. Objective Events Produced

### EXPLORE success

```kotlin
QuestObjectiveEvent(
    type = QuestObjectiveEventType.LOCATION_VISITED,
    targetId = progress.definition.targetId,
    islandId = progress.definition.islandId,
    amount = QuestFieldResolver.progressPerSuccess(progress.definition.rarity),
    sourceQuestId = questId,
)
```

### COLLECT success

```kotlin
QuestObjectiveEvent(
    type = QuestObjectiveEventType.ITEM_ACQUIRED,
    targetId = progress.definition.targetId,
    islandId = progress.definition.islandId,
    amount = QuestFieldResolver.progressPerSuccess(progress.definition.rarity),
    sourceQuestId = questId,
)
```

`ITEM_ACQUIRED` here means the quest-specific objective supply was secured. It does not call `InventoryEngine.grant` and does not create a tradable item.

## 16. Actions That Must Not Progress These Quests

The following remain unrelated and must not emit field objective events in this slice:

- `ShopEngine.buy`;
- `ShopEngine.sell`;
- `InventoryEngine.grant`;
- starter kits;
- scenario/arc rewards;
- quest turn-in rewards;
- arbitrary inventory `USE`, `EQUIP`, `UNEQUIP`, `DISCARD`;
- campaign island arrival;
- voyage completion;
- ordinary scenario/arc choices.

This prevents buying four bandages or receiving ordinary loot from satisfying `medical-supplies`, and prevents merely sailing to an island from satisfying `forgotten-ruins`.

## 17. Handler Routing

`StormglassGameplayCommandHandler` owns one `QuestFieldCoordinator` instance.

For `GameplayWireCommand.QuestAction`:

```text
START_BOSS       -> QuestBossCoordinator
START_HUNT       -> QuestHuntCoordinator
EXPLORE_SITE     -> QuestFieldCoordinator(EXPLORE_SITE)
SEARCH_SUPPLIES  -> QuestFieldCoordinator(SEARCH_SUPPLIES)
all others       -> existing quest management path
```

Manual `PROGRESS` continues through `QuestEngine.progress`, which rejects EXPLORE/COLLECT/HUNT/BOSS.

No existing subtype number changes.

## 18. Metadata

Every accepted field attempt event records:

```text
meta.questFieldAction
meta.questId
meta.questFieldAttempt
meta.questFieldActor
meta.questFieldCheck
meta.questFieldRoll
meta.questFieldModifier
meta.questFieldTotal
meta.questFieldDc
meta.questFieldSuccess
meta.questFieldEnergySpent=1
```

On success also record:

```text
meta.questObjective=LOCATION_VISITED | ITEM_ACQUIRED
meta.questObjectiveSourceQuest=<questId>
meta.questObjectiveTarget=<targetId>
meta.questObjectiveAmount=<1|2|3|4>
meta.questProgress=<new progress>
```

Metadata is descriptive, never authoritative.

## 19. Presenter / Android Behavior

No new `GameScreen`, Android dispatcher kind or wire parser is required. Existing generic `QUEST` action parsing carries the new action strings.

### ACTIVE EXPLORE

When actor energy is positive:

- progress 0 label: `Explorar ruínas • <title>`
- progress >0 label: `Continuar exploração • <title>`
- action id: `EXPLORE_SITE|<questId>|1`

### ACTIVE COLLECT

When actor energy is positive:

- progress 0 label: `Buscar suprimentos • <title>`
- progress >0 label: `Continuar busca de suprimentos • <title>`
- action id: `SEARCH_SUPPLIES|<questId>|1`

When actor energy is zero, no field action is offered. The contract remains visible and the body indicates that 1 PE is required.

### READY_TO_TURN_IN

Only existing `TURN_IN` is offered for that contract.

### Remaining migration types

RESCUE, ESCORT and INVESTIGATE keep `Registrar progresso` / `PROGRESS`.

### Visible last roll

If field-result flags exist, the active/ready contract body includes one compact authoritative line, for example:

```text
Último teste: P2 • INT + MEDICINE • d20 14 + 3 = 17 vs CD 15 • SUCESSO • -1 PE
```

Failure uses `FALHA`. The line is derived from authoritative world flags, not client-local UI state.

## 20. Failure / Invalid-State Rules

Reject without mutation for:

- unknown actor;
- incomplete profile;
- nonpositive P1/P2 HP;
- actor energy below 1;
- missing quest;
- non-ACTIVE quest;
- wrong island;
- wrong field action for quest type;
- action amount other than 1;
- active structured or legacy combat;
- active voyage;
- active duel;
- HUNT/BOSS combat origin binding;
- incomplete arc;
- incomplete legacy scenario when no arc exists;
- reused command id with different fingerprint.

A valid D20 failure is not an invalid state. It spends 1 PE, increments attempt count, writes last-roll state and leaves quest progress unchanged.

## 21. Persistence / Compatibility

No structured state schema changes.

- `WorldStateCodec.CURRENT_VERSION` remains 11.
- v1-v10 decode behavior remains unchanged.
- network `PROTOCOL_VERSION` remains 5.
- `QuestAction` remains gameplay wire subtype 9.
- `DuelAction` remains subtype 10.
- no new canonical hash block is introduced.
- existing `worldFlags` naturally carry attempt state through snapshot, event replay and canonical hashing.
- `CombatEngine.kt` must remain unchanged.
- `QuestBossFactory.kt` and `QuestBossCoordinator.kt` should remain unchanged.
- HUNT coordinator/factory behavior must remain green.

## 22. SOLO Behavior

No companion AI is required for field checks. A field attempt is a single actor action, unlike simultaneous combat/voyage rounds.

In SOLO, P1 executes field attempts normally. The AI companion does not automatically spend P2 energy to farm additional attempts.

Existing companion combat/voyage/story planning remains unchanged.

## 23. Real TCP Reconnect Contract

Add a real loopback TCP integration lifecycle that proves authoritative convergence for field objectives:

1. Start HOST_COOP in a hub-compatible state with complete wounded players and known energy.
2. Activate one EXPLORE and one COLLECT contract, plus same-target distractor contracts where useful.
3. Connect P2 over real TCP.
4. P2 performs one field attempt.
5. Refresh and assert host/P2 convergence for energy, attempt ordinal, last-roll flags, progress and canonical hash.
6. Disconnect P2.
7. Recreate a fresh P2 replica from a stale confirmed snapshot and reconnect.
8. Assert delta/snapshot recovery restores exact field state and hash.
9. Continue authoritative field attempts over TCP until EXPLORE becomes READY_TO_TURN_IN.
10. Prove same-target unrelated quest did not advance.
11. Repeat enough COLLECT attempts to reach READY_TO_TURN_IN.
12. Prove ordinary shop purchase/inventory grant did not add objective progress.
13. `TURN_IN` each contract once and verify rewards once.
14. Retry exact turn-in command id and exact field-attempt command id; neither duplicates reward, PE spend, attempt ordinal or progress.

The test must tolerate deterministic success/failure by issuing attempts until the fixed generated success count is reached, with an explicit safe attempt bound and enough initial energy. It must assert at least one observed failure path using a chosen deterministic fixture seed if needed.

## 24. Required TDD Coverage

### QuestEngine / Router

- manual `PROGRESS` rejects BOSS/HUNT/EXPLORE/COLLECT;
- RESCUE/ESCORT/INVESTIGATE manual progress remains green;
- objective progress accepts ACTIVE HUNT/EXPLORE/COLLECT only;
- LOCATION_VISITED advances only exact EXPLORE match;
- ITEM_ACQUIRED advances only exact COLLECT match;
- bound source quest isolates same-target contracts;
- wrong source/target/island/type no-op;
- ISLAND_VISITED remains no-op;
- future rescue/escort/clue events remain no-op;
- clamp/READY transition remains correct.

### Resolver

- exact CD table;
- exact rarity progress multiplier;
- EXPLORE modifier and deterministic tie priority;
- COLLECT best-check selection and deterministic tie priority;
- identical authoritative inputs yield identical roll/result;
- attempt ordinal changes the deterministic roll stream;
- actor/action/quest identity participate in seed;
- roll is always 1..20;
- `success == total >= CD`;
- invalid type/action reject.

### Coordinator

- valid EXPLORE consumes exactly 1 PE and increments attempt ordinal;
- valid COLLECT consumes exactly 1 PE and increments attempt ordinal;
- failure consumes PE but gives zero progress;
- success emits correct bound event and exact progress amount;
- final success transitions READY_TO_TURN_IN;
- no early Berries/items/PEV/faction/bounty/reward flags;
- same-target quest isolation;
- command retry idempotency;
- command id collision rejection;
- zero energy rejection;
- profile/HP/island/status/action/amount validation;
- combat/voyage/duel/non-hub rejection;
- metadata exactness;
- field state cleanup on TURN_IN/FAIL.

### Handler / Presentation

- routes EXPLORE_SITE and SEARCH_SUPPLIES to field coordinator;
- existing START_HUNT and START_BOSS routing unchanged;
- manual EXPLORE/COLLECT progress rejected;
- RESCUE/ESCORT/INVESTIGATE manual actions retained;
- correct EXPLORE/COLLECT labels at zero and partial progress;
- zero PE hides field action and shows requirement;
- last authoritative D20 line renders;
- READY shows TURN_IN only;
- no Android dispatcher change required.

### Negative integration

- shop buy does not progress COLLECT;
- inventory grant/reward does not progress COLLECT;
- campaign arrival does not progress EXPLORE;
- ordinary arc/scenario actions do not progress either.

### Real TCP

Use the reconnect contract in section 23 and assert full canonical convergence.

## 25. Final Verification Gate

Before completion:

```bash
bash tools/run-core-tests.sh
gradle --no-daemon --stacktrace :app:assembleDebug
test -s app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

If local Android build remains unavailable, use a temporary PR workflow against the exact source head, then remove it and prove no post-build `app/` or `core/` changes.

Explicitly verify:

- full suite `N/N passed`;
- HUNT tests green;
- quest BOSS tests green;
- PvP tests green;
- narrative arc/scenario tests green;
- real TCP field reconnect green;
- `CombatEngine.kt` unchanged;
- protocol v5;
- QuestAction subtype 9;
- DuelAction subtype 10;
- snapshot v11 and old decode compatibility;
- temporary Android workflow absent from final diff;
- APK SHA-256 recorded from observed build output only.

## 26. Migration After This Slice

After EXPLORE + COLLECT are stable:

1. RESCUE -> `NPC_RESCUED` from a real rescue encounter/source.
2. ESCORT -> `ESCORT_ARRIVED` from a persistent escort/voyage source.
3. INVESTIGATE -> `CLUE_DISCOVERED` from investigation scene sources.
4. Remove generic manual `PROGRESS` after all quest types have real authoritative event sources.
