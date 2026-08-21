# Consensual PvP Duel Design

## Goal

Add a real two-player PvP mode to GRAND LINE DUO without destabilizing the existing cooperative PvE combat path.

The first PvP slice is a consensual, non-lethal duel between P1 and P2. It must be deterministic, host-authoritative, persistent, reconnect-safe, LAN-compatible, and integrated with the existing character progression, equipment, Haki, Devil Fruit, energy and presentation systems.

PvP is intentionally implemented as its own duel subsystem rather than forcing player-versus-player behavior into the current `CombatEngine`, whose model is explicitly two allied players versus one enemy with enemy telegraphs and co-op combos.

## Scope

In scope:

- P1 or P2 can challenge the other human player;
- the challenged player can accept or decline;
- solo mode cannot start PvP against the companion AI;
- deterministic round-based PvP with simultaneous locked actions;
- reuse of existing `CombatActionType` vocabulary where appropriate;
- equipment modifiers, Haki, Devil Fruit, energy and mastery integration;
- no free healing when entering or leaving a duel;
- non-lethal defeat at 1 HP;
- no berries, bounty, loot or quest reward for winning;
- host authority, idempotent commands and canonical-state convergence;
- save/restart/reconnect support for pending, active and finished duels;
- real TCP LAN tests;
- Android presentation for challenge, accept/decline, duel actions and closure.

Out of scope for this slice:

- lethal or permanent-death PvP;
- open-world ambushes or non-consensual PvP;
- ranked ladders, matchmaking or internet servers;
- spectator mode;
- wagering, betting or loot transfer;
- team PvP;
- cryptographic anti-cheat or hostile modified-client protection;
- custom PvP animations or a new rendering engine;
- balance overhaul of every technique.

A later `blood duel`/hardcore PvP mode may build on this subsystem, but it is not part of the first implementation.

## Why a Separate Duel Engine

Three approaches were considered:

1. **Recommended: dedicated `DuelState` + `DuelEngine`**
   - Reuses action vocabulary and progression modifiers.
   - Keeps PvP symmetry explicit.
   - Avoids infecting PvE code with special cases for enemy-less combat.
   - Safest for existing quest bosses and narrative bosses.

2. **Generalize `CombatEngine` into PvE/PvP**
   - Architecturally unified in theory.
   - High regression risk because the existing engine assumes an `enemy`, enemy telegraphs and cooperative P1/P2 combos.
   - Rejected for this slice.

3. **Represent the opposing player as `EnemyCombatant`**
   - Fastest implementation.
   - Produces asymmetric rules, incorrect power/modifier behavior and long-term technical debt.
   - Rejected.

## Domain Model

Add a focused `grandlineduo.game.duel` package.

### `DuelState`

Persistent authoritative state:

- `duelId`: stable deterministic identifier derived from `campaignId + challenge commandId + challengerId + challengedId`;
- `challengerId`: P1 or P2;
- `challengedId`: the other human player;
- `phase`: `PENDING`, `ACTIVE`, `FINISHED`;
- `round`: 0 while pending, starts at 1 when accepted;
- `fighters`: empty while pending, then a map of player id to `DuelFighter`;
- `lockedActions`: map of submitted player actions for the current round;
- `winnerId`: nullable until a single-winner finish;
- `loserId`: nullable until a single-loser finish;
- `finishReason`: nullable while unfinished, then `KNOCKOUT` or `DOUBLE_KNOCKOUT`.

`DuelFighter` contains:

- `playerId`;
- `hp`;
- `maxHp`;
- `setupReady: Boolean`.

`setupReady` is explicit persistent state. `SETUP` sets it for that fighter's next offensive action; using `FINISHER` or another offensive action that consumes the setup clears it. This prevents setup behavior from depending on implicit log/history reconstruction.

Character progression, inventory, equipment, energy and mastery remain authoritative in the existing world/profile systems rather than being duplicated into duel state.

### World integration

Add `activeDuel: DuelState?` to `WorldState`.

A duel is mutually exclusive with activities that require exclusive player control:

- `activeCombat` must be null;
- `activeVoyage` must be null;
- a second duel cannot start while `activeDuel != null` in any phase;
- a duel cannot start from a terminal hardcore defeat/game-over state.

An `activeArc` without active combat does not by itself block a duel; the duel is treated as a side activity. Existing arc state must remain untouched.

Pending invitations are persisted as `activeDuel.phase == PENDING` so save/restart and reconnect preserve who challenged whom.

Because this is structured gameplay state, persistence must encode it explicitly rather than hiding it in `worldFlags`.

## Persistence and Hashing

Extend `WorldStateCodec` with the next snapshot schema version and maintain backward compatibility for existing saves with no duel data.

Requirements:

- old snapshots decode with `activeDuel = null`;
- new snapshots encode/decode pending, active and finished duel state exactly;
- `CanonicalStateHasher` includes duel state deterministically;
- when `activeDuel == null`, preserve the previous canonical representation/hash behavior using the same compatibility strategy already used by the project;
- maps/actions serialize in deterministic player-id order;
- host snapshot, client snapshot and recovered durable state converge byte-for-byte where existing tests require it.

## Challenge Lifecycle

Introduce `GameplayWireCommand.DuelAction` rather than overloading quest/world commands.

Supported lifecycle actions:

- `CHALLENGE`
- `ACCEPT`
- `DECLINE`
- `CLOSE`

`DuelAction` fingerprints include actor and action type. `CHALLENGE` does not need an explicit target because this product supports exactly two human players; the opponent is the other human participant resolved by the authoritative session state.

Flow:

1. P1 or P2 selects `Desafiar para duelo`.
2. Host validates two-human session state and no incompatible activity.
3. Host creates persisted `DuelState(phase=PENDING, round=0, fighters=empty)` with deterministic `duelId`.
4. Challenged player sees `Aceitar duelo` and `Recusar duelo`.
5. `DECLINE` clears `activeDuel`; no resources change.
6. `ACCEPT` copies both players' current HP/max HP exactly into `DuelFighter`, with `setupReady=false`; there is no healing.
7. Phase becomes `ACTIVE`, round 1 begins.
8. On knockout/double knockout, the duel moves to `FINISHED`, player HP is synchronized with the duel result and the non-lethal floor is applied.
9. `CLOSE` is valid only in `FINISHED`; it clears `activeDuel` without healing or refunding energy.

Challenge creation is rejected in SOLO because P2 there is a deterministic companion rather than a second human participant.

## Duel Round Rules

The duel uses simultaneous action locking.

Each living fighter submits one action for the current round. Resolution occurs only after both have locked one action.

Reuse the existing `CombatActionType` vocabulary:

- `ATTACK`
- `DEFEND`
- `DODGE`
- `SETUP`
- `FINISHER`
- `HAKI_BUSOSHOKU`
- `HAKI_KENBUNSHOKU`
- `HAKI_HAOSHOKU`
- `DEVIL_FRUIT`

The PvP formulas live in `DuelEngine`; they do not call `CombatEngine.resolve` because there is no PvE enemy/telegraph/co-op-combo model in a duel.

### Baseline interaction rules

The first balance pass favors predictable counterplay over maximum complexity:

- `ATTACK`: standard direct damage;
- `DEFEND`: strongly reduces incoming direct damage;
- `DODGE`: deterministic seeded avoidance/reduction against direct offense;
- `SETUP`: low/no immediate damage and sets `setupReady=true` for the fighter's next offensive action;
- `FINISHER`: high damage, receiving an explicit bonus and consuming `setupReady` when available;
- `HAKI_BUSOSHOKU`: offensive power action with existing character/equipment modifier contribution;
- `HAKI_KENBUNSHOKU`: defensive/evasive power action improving avoidance against opponent offense;
- `HAKI_HAOSHOKU`: high-impact offensive/control action subject to existing unlock/energy rules;
- `DEVIL_FRUIT`: offensive/special action using existing technique eligibility and energy/mastery accounting.

Exact damage constants belong in the implementation plan/tests. The architectural invariant is deterministic resolution from duel seed + round + both locked actions + explicit duel state + authoritative modifiers.

After each resolved non-terminal round:

- increment `round` once;
- clear `lockedActions`;
- retain or consume `setupReady` according to the resolved actions;
- synchronize duel HP to `WorldState.players`.

### Simultaneous knockout

If both fighters would fall below the non-lethal floor in the same resolved round, terminate with `finishReason=DOUBLE_KNOCKOUT`, `winnerId=null`, `loserId=null`, and both players at 1 HP.

No arbitrary action order may decide a simultaneous terminal result.

## Hidden Choice Semantics

The official UI must not reveal the opponent's locked action before both players have submitted.

The replicated authoritative state may contain `lockedActions`; presentation exposes only readiness (`oponente pronto`) until the round resolves. After resolution, both actions may appear in the round log.

This first LAN slice does **not** claim cryptographic secrecy against a hostile modified client inspecting raw replicated state. Commit/reveal cryptography is explicitly out of scope. The threat model is normal trusted LAN play using the official client.

## Modifiers, Haki, Devil Fruit and Energy

Reuse existing progression systems rather than duplicating PvP-specific copies.

### Ordinary actions

`DuelCoordinator` obtains equipment/combat modifiers through the existing resolver and passes them into `DuelEngine` for the correct fighter only.

### Power actions

`StormglassGameplayCommandHandler` keeps `PowerTechniqueEngine.prepare(...)` as the authoritative gate for:

- technique ownership/unlock;
- energy cost;
- mastery/use-count progression;
- power-specific metadata.

Routing when `activeDuel?.phase == ACTIVE`:

1. prepare the power action on authoritative world state;
2. translate the prepared technique to the existing `CombatActionType` power category;
3. pass prepared world + action + metadata into `DuelCoordinator.submitPreparedAction(...)`;
4. commit energy/mastery and duel action/resolution atomically in the same authoritative event.

A rejected or duplicate duel action must not consume energy or increment mastery twice.

Energy spent during a duel remains spent after the duel. There is no automatic restoration.

## HP and Non-Lethal Defeat

Entering a duel copies each player's current HP exactly. It never heals to max HP.

During active rounds, `DuelFighter.hp` is the duel's combat HP. After each resolved round, player HP in `WorldState.players` is synchronized to the duel HP so persistence and the rest of the game remain coherent.

Non-lethal terminal floor:

- a fighter that would reach 0 or below is written as 1 HP when the duel terminates;
- the winner retains HP produced by the resolved round, minimum 1;
- a double knockout leaves both at 1 HP;
- no post-duel healing occurs;
- consumed energy remains consumed;
- no berries, loot, bounty, quest progress or reputation reward is granted simply for duel victory.

This makes PvP costly without allowing consensual sparring to permanently kill a campaign character in the first slice.

## Command Routing

Add `GameplayWireCommand.DuelAction` and a new wire codec subtype.

Existing `CombatAction` and `PowerAction` are reused while a duel is ACTIVE:

- if `activeDuel?.phase == ACTIVE` and `activeCombat == null`, `CombatAction` routes to `DuelCoordinator`;
- otherwise if `activeDuel == null` and `activeCombat != null`, preserve current quest-boss/arc routing;
- `PowerAction` follows the same origin rules after authoritative preparation;
- if duel and PvE combat are both non-null, reject rather than guessing which system owns the command;
- lifecycle commands (`CHALLENGE`, `ACCEPT`, `DECLINE`, `CLOSE`) route only through `DuelCoordinator`.

This keeps one action vocabulary for Android combat controls while maintaining separate authoritative resolution engines.

## `DuelCoordinator`

Add a host-authoritative coordinator analogous in responsibility to the existing combat coordinators.

Responsibilities:

- create and validate challenges;
- accept/decline pending challenges;
- close finished duels;
- submit ordinary duel actions;
- submit prepared power actions atomically;
- resolve a round only after both fighters lock actions;
- synchronize HP into `WorldState.players` after resolution;
- mark knockout/double-knockout outcomes;
- persist through `HostReplica`, snapshot store and durable store;
- preserve idempotency for repeated command ids;
- attach event metadata such as duel id, phase, round and finish reason.

It does not own inventory, power unlock logic, LAN transport or Android rendering.

## Fairness and Authority

The host is authoritative for all duel state and deterministic resolution.

The duel seed derives from campaign seed + deterministic duel id, with round-specific random streams. The same authoritative inputs must produce the same result on replay/recovery.

Neither player may:

- challenge themselves;
- accept/decline a challenge not addressed to them;
- close an unfinished duel;
- submit two actions in the same round;
- act after the duel is finished;
- submit a PvP action while pending;
- use a locked/unowned power technique;
- start a duel during incompatible PvE combat/voyage state.

## Solo Behavior

PvP is for two human players only.

In SOLO:

- do not show `Desafiar para duelo`;
- any forged `CHALLENGE` command is rejected by host validation;
- existing companion planner is never invoked as a PvP opponent;
- PvE combat remains unchanged.

## Android Presentation

Reuse existing generic gameplay/presentation structures where possible; do not create a parallel Activity architecture.

### Normal two-player hub/exploration state

When no incompatible activity is active, expose:

- `Desafiar para duelo`

### Pending challenge

Challenger sees:

- `Aguardando resposta de <jogador>`
- no combat controls

Challenged player sees:

- `Aceitar duelo`
- `Recusar duelo`

### Active duel

Use a dedicated duel presentation mode/screen state if required by current presenter architecture, showing:

- both player names;
- current HP/max HP;
- current round;
- own available actions;
- own locked/readiness state;
- opponent readiness without revealing opponent action before resolution;
- last resolved round result/log;
- available Haki/Devil Fruit techniques through existing power controls.

### Finished duel

Show:

- winner/loser or `Empate — nocaute duplo`;
- remaining HP;
- `Encerrar duelo`.

No reward banner is displayed.

## Error Handling and Invariants

- challenge in solo: reject with no mutation;
- challenge self: reject;
- challenge while combat/voyage/duel is active: reject;
- accept by wrong actor: reject;
- decline by wrong actor: reject;
- close before FINISHED: reject;
- combat action while phase is not ACTIVE: reject;
- duplicate action by same player in same round: reject without duplicate resource consumption;
- invalid/locked power: reject before duel mutation;
- active duel plus active PvE combat is invalid and commands reject rather than guess routing;
- closing a duel does not heal or refund energy;
- duel never grants quest progress/reward by itself;
- finished non-lethal duel never leaves a player below 1 HP.

## Wire Compatibility

A new `DuelAction` wire subtype is required because lifecycle semantics do not fit safely into quest/world actions.

Requirements:

- allocate the next subtype id without renumbering existing subtypes;
- old command decoding remains unchanged;
- new clients encode/decode `CHALLENGE`, `ACCEPT`, `DECLINE`, `CLOSE`;
- active-round ordinary combat continues using existing `CombatAction`;
- active-round powers continue using existing `PowerAction`.

## Testing Strategy

Use TDD in layers.

### 1. `DuelEngineTest`

- same seed/state/actions => identical result;
- attack/defend interaction;
- dodge/kenbunshoku defensive interaction;
- setup persists explicitly and boosts/gets consumed by finisher;
- equipment modifiers affect only their owner;
- Haki/Devil Fruit category modifiers apply correctly;
- knockout clamps loser to 1 HP;
- simultaneous knockout produces deterministic `DOUBLE_KNOCKOUT`;
- no PvE co-op combo or enemy telegraph assumptions leak into duel logic.

### 2. `DuelCoordinatorTest`

- valid P1->P2 and P2->P1 challenge;
- deterministic duel id from challenge command;
- solo challenge rejected;
- wrong player cannot accept/decline;
- accept copies current HP without healing;
- incompatible combat/voyage state rejects challenge;
- first action locks without resolving;
- second action resolves exactly once;
- duplicate commands are idempotent;
- prepared power consumes energy/mastery exactly once;
- knockout synchronizes world HP and grants no rewards;
- double knockout leaves both at 1 HP;
- close clears duel without healing/refund.

### 3. Persistence/hash tests

- snapshot round-trip pending duel;
- snapshot round-trip active duel with one/both locked actions and setup state;
- snapshot round-trip finished duel;
- legacy snapshot decodes with null duel;
- canonical hash deterministic regardless of map iteration order;
- null duel preserves legacy hash behavior;
- host recovery resumes same duel/round.

### 4. Handler/presenter tests

- `DuelAction` routes lifecycle correctly;
- `CombatAction` routes to duel only for ACTIVE duel;
- `PowerAction` routes through `PowerTechniqueEngine.prepare` then duel coordinator;
- invalid simultaneous duel+PvE state rejects;
- two-human session shows challenge;
- solo hides challenge;
- pending challenge UI differs for challenger/challenged;
- active duel hides opponent action but shows readiness;
- finished duel shows correct result and close action.

### 5. Real TCP LAN integration

At minimum:

1. P1 challenges P2 over TCP.
2. P2 receives pending challenge and accepts.
3. Both submit actions across the real LAN command path.
4. Host/client converge after each resolved round.
5. P2 disconnects during an active duel.
6. Fresh client replica reconnects and restores authoritative duel/round/HP/locks.
7. Duel completes after reconnect.
8. Duplicate final action/close command does not double-apply HP, energy or mastery.
9. Host/client canonical hashes and snapshots converge.

### 6. Regression and Android build

Final verification must include:

- entire core test suite;
- all existing quest boss/arc boss/LAN/persistence tests green;
- `:app:assembleDebug` against exact final source head;
- no temporary verification workflow left in final PR diff.

## Compatibility and Rollout

- Existing PvE `CombatEngine` behavior remains unchanged.
- Existing quest BOSS routing remains unchanged when `activeDuel == null`.
- Existing arc combat remains unchanged when `activeDuel == null`.
- Existing saves decode with no duel.
- Existing solo companion behavior remains PvE-only.
- Duel rewards are intentionally absent, so the feature cannot be farmed for berries/bounty/quest progress.
- The subsystem boundary leaves room for a later lethal `blood duel` mode without changing PvE combat semantics.
