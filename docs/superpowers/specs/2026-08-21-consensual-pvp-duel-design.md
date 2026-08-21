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
- save/restart/reconnect support for pending and active duels;
- real TCP LAN tests;
- Android presentation for challenge, accept/decline and duel actions.

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

- `duelId`: stable deterministic identifier;
- `challengerId`: P1 or P2;
- `challengedId`: the other human player;
- `phase`: `PENDING`, `ACTIVE`, `FINISHED`;
- `round`: starts at 1 when accepted;
- `fighters`: map of player id to duel fighter HP/max HP;
- `lockedActions`: map of submitted player actions for the current round;
- `winnerId`: nullable until finished;
- `loserId`: nullable until finished;
- `finishReason`: nullable, initially only `KNOCKOUT`;
- `startedAtEventId` or equivalent stable origin metadata if required by existing persistence conventions.

A `DuelFighter` carries only duel-specific combat values such as current HP/max HP. Character progression, inventory, equipment, energy and mastery remain authoritative in normal `WorldState.players` / profile/inventory systems rather than being duplicated into the duel model.

### World integration

Add `activeDuel: DuelState?` to `WorldState`.

A duel is mutually exclusive with activities that already require exclusive player control:

- `activeCombat` must be null;
- `activeVoyage` must be null;
- a duel cannot start while another duel is pending/active;
- the first slice also rejects duel start during a terminal hardcore defeat state.

Pending invitations are persisted as `activeDuel.phase == PENDING` so save/restart and reconnect preserve who challenged whom.

Because this is real structured state, persistence must encode it explicitly rather than hiding it in `worldFlags`.

## Persistence and Hashing

Extend `WorldStateCodec` with the next snapshot schema version and maintain backward compatibility for existing saves with no duel data.

Requirements:

- old snapshots decode with `activeDuel = null`;
- new snapshots encode/decode pending, active and finished duel state exactly;
- `CanonicalStateHasher` includes duel state deterministically;
- when `activeDuel == null`, legacy campaigns should retain their prior canonical representation/hash behavior where the current hasher's compatibility strategy permits it;
- all maps/actions are serialized in deterministic player-id order;
- host snapshot, client snapshot and recovered durable state converge byte-for-byte where existing tests require it.

## Challenge Lifecycle

Introduce `GameplayWireCommand.DuelAction` rather than overloading quest/world commands.

Supported lifecycle actions:

- `CHALLENGE`
- `ACCEPT`
- `DECLINE`

`DuelAction` fingerprints include actor and action type so duplicate command ids remain idempotent through the existing command/event path.

Flow:

1. P1 or P2 selects `Desafiar para duelo`.
2. Host validates that the session has two human players and no incompatible active activity.
3. Host creates persisted `DuelState(phase=PENDING)`.
4. Challenged player sees `Aceitar duelo` and `Recusar duelo`.
5. `DECLINE` clears `activeDuel`; no resources change.
6. `ACCEPT` snapshots both players' current HP/max HP into duel fighters exactly as they are. There is no healing.
7. Phase becomes `ACTIVE`, round 1 begins.
8. On knockout, the duel moves to `FINISHED`, world/player HP is synchronized with the duel result, and the loser is clamped to 1 HP.
9. Finished-duel presentation exposes a return/close action that clears `activeDuel` without restoring HP or energy.

Challenge creation is rejected in SOLO because P2 there is a deterministic companion rather than a second human participant.

## Duel Round Rules

The duel uses simultaneous action locking.

Each living fighter submits one action for the current round. Resolution occurs only after both have locked an action.

Use the existing `CombatActionType` vocabulary:

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
- `DODGE`: chance/deterministic roll to avoid or heavily reduce direct attacks based on duel seed + round;
- `SETUP`: low/no immediate damage and grants a one-round offensive setup bonus represented explicitly in duel state if required;
- `FINISHER`: high damage; strongest after a prior setup and less efficient when thrown raw;
- `HAKI_BUSOSHOKU`: offensive power action with existing character/equipment modifier contribution;
- `HAKI_KENBUNSHOKU`: defensive/evasive power action that improves avoidance against the opponent's offensive action;
- `HAKI_HAOSHOKU`: high-impact offensive/control action subject to existing unlock/energy rules;
- `DEVIL_FRUIT`: offensive/special action using existing technique eligibility and energy/mastery accounting.

Exact damage constants belong in the implementation plan/tests, not in this architectural spec, so they can be tuned without changing the subsystem boundaries. The invariant is deterministic resolution from duel seed + round + both locked actions + authoritative modifiers.

### Simultaneous knockout

If both fighters would be reduced below the non-lethal floor in the same round, resolve a draw-like terminal result deterministically instead of granting an arbitrary first-mover advantage.

For this slice, add `finishReason = DOUBLE_KNOCKOUT` and leave both players at 1 HP with no winner/loser.

## Hidden Choice Semantics

The normal UI must not reveal the opponent's locked action before both players have submitted.

The authoritative host may hold both actions and the replicated state may internally contain the locked actions, but presentation must expose only readiness (`opponent ready`) until resolution.

This first LAN slice does **not** claim cryptographic secrecy against a hostile modified client inspecting raw replicated state. Commit/reveal cryptography is explicitly out of scope. The threat model is normal trusted LAN play using the official client.

## Modifiers, Haki, Devil Fruit and Energy

Reuse existing progression systems rather than duplicating PvP-specific copies.

### Ordinary actions

`DuelCoordinator` obtains equipment/combat modifiers through the existing resolver and passes them into `DuelEngine`.

### Power actions

`StormglassGameplayCommandHandler` keeps `PowerTechniqueEngine.prepare(...)` as the authoritative gate for:

- technique ownership/unlock;
- energy cost;
- mastery/use-count progression;
- power-specific metadata.

Routing when `activeDuel?.phase == ACTIVE`:

1. prepare the power action on the authoritative world;
2. translate the prepared technique to the existing `CombatActionType` power category;
3. pass prepared world + action + metadata into `DuelCoordinator.submitPreparedAction(...)`;
4. commit energy/mastery and duel resolution atomically in the same authoritative event.

A rejected/invalid duel action must not consume energy or increment mastery.

Energy spent during a duel remains spent after the duel. There is no automatic restoration.

## HP and Non-Lethal Defeat

Entering a duel copies each player's current HP exactly. It never heals to max HP.

During active rounds, duel fighter HP is authoritative for the duel. After each resolved round, world player HP is synchronized so save/recovery and the rest of the game remain coherent.

Non-lethal floor:

- a fighter that would reach 0 or below is set to 1 HP when the duel terminates;
- the opponent retains the HP produced by the resolved round, bounded to at least 1 HP in a double knockout;
- no post-duel healing occurs;
- consumed energy remains consumed;
- no berries, loot, bounty, quest progress or reputation reward is granted simply for duel victory.

This makes PvP costly without allowing consensual sparring to permanently kill a campaign character in the first slice.

## Command Routing

Add `GameplayWireCommand.DuelAction` and a new wire codec subtype.

Existing `CombatAction` and `PowerAction` are reused while a duel is ACTIVE:

- if `activeDuel?.phase == ACTIVE`, `CombatAction` routes to `DuelCoordinator`;
- otherwise if `activeCombat != null`, preserve current quest-boss/arc combat routing;
- `PowerAction` similarly prefers active duel routing only when a valid ACTIVE duel exists;
- malformed duel state rejects rather than silently falling back to PvE combat.

Pending/finished duel lifecycle commands use `DuelAction`.

This keeps one action vocabulary for the Android combat controls while maintaining separate authoritative resolution engines.

## `DuelCoordinator`

Add a host-authoritative coordinator analogous in responsibility to the existing combat coordinators.

Responsibilities:

- create and validate challenges;
- accept/decline pending challenges;
- submit ordinary duel actions;
- submit prepared power actions atomically;
- resolve a round only after both fighters lock actions;
- synchronize HP into `WorldState.players`;
- mark knockout/double-knockout outcomes;
- close a finished duel;
- persist through `HostReplica`, snapshot store and durable store;
- preserve idempotency for repeated command ids;
- attach useful event metadata such as duel id, phase, round and finish reason.

It does not own inventory, power unlock logic, LAN transport or Android rendering.

## Fairness and Authority

The host is authoritative for all duel state and deterministic resolution.

The duel seed is derived deterministically from campaign seed + duel id, with round-specific random streams. The same authoritative inputs must produce the same result on replay/recovery.

Neither player may:

- challenge themselves;
- accept/decline a challenge not addressed to them;
- submit two actions in the same round;
- act after being knocked out;
- submit a PvP action while the duel is pending or finished;
- use a locked/unowned power technique;
- start a duel during incompatible PvE/voyage state.

## Solo Behavior

PvP is for two human players only.

In SOLO:

- do not show `Desafiar para duelo`;
- any forged `CHALLENGE` command is rejected by host validation;
- the existing companion planner is never invoked as a PvP opponent;
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

Use a dedicated duel presentation mode/screen state if needed by the current presenter architecture, showing:

- both player names;
- current HP/max HP;
- current round;
- own available actions;
- own locked/readiness state;
- opponent readiness without revealing the opponent action before round resolution;
- last round result/log;
- available Haki/Devil Fruit techniques through the existing power controls.

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
- combat action while phase is not ACTIVE: reject;
- duplicate action by same player in same round: reject without duplicate resource consumption;
- invalid/locked power: reject before duel mutation;
- active duel plus active PvE combat is an invalid state and commands reject rather than guess routing;
- closing a duel does not heal or refund energy;
- duel never grants quest progress/reward by itself;
- finished non-lethal duel never leaves a player below 1 HP.

## Wire Compatibility

A new `DuelAction` wire subtype is acceptable because the feature is not representable safely as a quest/world action.

Requirements:

- bump/add the next subtype id without renumbering existing subtypes;
- old command decoding remains unchanged;
- new clients encode/decode `CHALLENGE`, `ACCEPT`, `DECLINE`, and `CLOSE` lifecycle actions;
- active-round ordinary combat continues using existing `CombatAction`;
- active-round powers continue using existing `PowerAction`.

## Testing Strategy

Use TDD in layers.

### 1. `DuelEngineTest`

- same seed/state/actions => identical result;
- attack/defend interaction;
- dodge/kenbunshoku defensive interaction;
- setup -> finisher advantage;
- equipment modifiers affect only their owner;
- Haki/Devil Fruit category modifiers apply correctly;
- knockout clamps loser to 1 HP;
- simultaneous knockout produces deterministic `DOUBLE_KNOCKOUT`;
- no PvE co-op combo or enemy telegraph assumptions leak into duel logic.

### 2. `DuelCoordinatorTest`

- valid P1->P2 and P2->P1 challenge;
- solo challenge rejected;
- wrong player cannot accept/decline;
- accept copies current HP without healing;
- incompatible combat/voyage state rejects challenge;
- first action locks without resolving;
- second action resolves exactly once;
- duplicate commands are idempotent;
- prepared power consumes energy/mastery exactly once;
- knockout synchronizes world HP and grants no rewards;
- close clears duel without healing/refund.

### 3. Persistence/hash tests

- snapshot round-trip pending duel;
- snapshot round-trip active duel with one/both locked actions;
- snapshot round-trip finished duel;
- legacy snapshot decodes with null duel;
- canonical hash deterministic regardless of map iteration order;
- host recovery resumes same duel/round.

### 4. Handler/presenter tests

- DuelAction routes lifecycle correctly;
- CombatAction routes to duel before PvE only when duel ACTIVE;
- PowerAction routes through `PowerTechniqueEngine.prepare` then duel coordinator;
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
6. Fresh client replica reconnects and restores the authoritative duel/round/HP.
7. Duel completes after reconnect.
8. Duplicate final action/close command does not double-apply HP, energy or mastery.
9. Host/client canonical hashes and snapshots converge.

### 6. Regression and Android build

Final verification must include:

- entire core test suite;
- all existing quest boss/arc boss/LAN/persistence tests green;
- `:app:assembleDebug` against the exact final source head;
- no temporary verification workflow left in the final PR diff.

## Compatibility and Rollout

- Existing PvE `CombatEngine` behavior remains unchanged.
- Existing quest BOSS routing remains unchanged when `activeDuel == null`.
- Existing arc combat remains unchanged when `activeDuel == null`.
- Existing saves decode with no duel.
- Existing solo companion behavior remains PvE-only.
- Duel rewards are intentionally absent, so the feature cannot be farmed for berries/bounty/quest progress.
- The subsystem boundary leaves room for a later lethal `blood duel` mode without changing PvE combat semantics.
