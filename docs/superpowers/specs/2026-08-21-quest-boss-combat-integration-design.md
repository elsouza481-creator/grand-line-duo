# Quest Boss Combat Integration Design

## Goal

Make `QuestType.BOSS` contracts launch and resolve a real authoritative boss fight instead of relying on manual quest progress, while reusing the existing persistent `CombatEngine`, LAN command path, save/reconnect behavior, equipment/Haki/Devil Fruit modifiers, and solo companion logic.

This block must not create a second combat engine, must not alter existing narrative arc boss behavior, and must preserve hardcore defeat.

## Scope

In scope:

- deterministic boss generation from an accepted BOSS quest;
- an explicit player action to start the accepted boss fight;
- authoritative combat in solo, host co-op and P2 LAN sessions;
- automatic quest progression on legitimate boss victory;
- automatic quest failure on party defeat;
- persistence/reconnect/idempotency for the quest-to-combat binding;
- Android quest presentation action for starting the boss encounter;
- tests for factory scaling, lifecycle, persistence and real TCP convergence.

Out of scope:

- new combat rules or a second combat engine;
- changing narrative arc boss stats or flow;
- free healing or checkpoints;
- multiple simultaneous quest boss fights;
- custom boss art/animations;
- automatic world-map pursuit or encounter navigation.

## Architecture

Add two focused quest-combat components:

1. `QuestBossFactory`
   - Pure deterministic factory.
   - Input: authoritative `WorldState`, accepted `QuestDefinition`, campaign seed.
   - Output: existing `CombatState`.
   - Uses current P1/P2 HP and max HP exactly as they are; it never heals players.
   - Uses `targetId` as the stable enemy identity and derives a readable enemy name from the quest title/target.
   - Uses quest rarity to scale HP and attack within bounded values.
   - Initial telegraph/target are deterministic from campaign seed + quest id/target.

2. `QuestBossCoordinator`
   - Host-only authority analogous to `ArcCombatCoordinator`.
   - Starts a boss fight for one accepted BOSS quest.
   - Submits ordinary `CombatActionType` actions through the existing `CombatEngine` and `CombatModifierResolver`.
   - Persists every accepted command through the existing `HostReplica` and durable/snapshot store path.
   - On VICTORY: clears `activeCombat`, removes the quest-combat binding, and advances that quest by its full required amount so it becomes `READY_TO_TURN_IN`.
   - On DEFEAT: keeps the terminal combat state for the existing hardcore/game-over presentation, removes the quest-combat binding, and moves the contract to permanent failed history with a boss-defeat reason. No quest reward is granted.

`ArcCombatCoordinator` remains unchanged in responsibility: it continues to resolve only combat belonging to an active narrative arc.

## Persistent combat origin

Do not introduce a new snapshot schema version only to identify combat origin.

Use the already-persisted and already-hashed `worldFlags` map as the binding:

- key: `quest.boss.active`
- value: active quest id

When a quest boss starts, `activeCombat` and this flag are committed atomically in the same authoritative event. On victory or defeat, the flag is removed in the same event that resolves the quest outcome.

Validation rules:

- the bound quest id must exist in `questBoard.active`;
- it must be `QuestType.BOSS`;
- it must belong to the current island;
- no combat or voyage may already be active;
- a quest boss cannot start when `quest.boss.active` is already present;
- narrative arc combat and quest boss combat are mutually exclusive because `WorldState.activeCombat` is singular.

This reuses existing snapshot/hash behavior and survives save, restart and reconnect without adding duplicate state.

## Player flow

A BOSS contract is not launched immediately on ACCEPT. Acceptance preserves the normal contract lifecycle and gives players a preparation window.

For an accepted BOSS quest in `ACTIVE` state, the quest screen exposes a `START_BOSS` quest action (Portuguese label such as `Enfrentar alvo`).

Flow:

1. Player accepts BOSS contract.
2. Quest remains ACTIVE, progress 0.
3. Player can prepare equipment/consumables while no combat is active.
4. Player presses `START_BOSS`.
5. Host validates the contract and creates deterministic `activeCombat` + `quest.boss.active=<questId>`.
6. Existing combat UI and `CombatAction` commands take over.
7. Victory automatically progresses the contract to `READY_TO_TURN_IN`.
8. Player returns to the contract board and explicitly turns it in to receive rewards.
9. Defeat permanently fails the contract and preserves the existing hardcore defeat path.

Manual `PROGRESS` is rejected for BOSS quests so a boss contract cannot be completed without defeating its target.

## Command routing

Extend existing `GameplayWireCommand.QuestAction` handling with `START_BOSS` rather than adding a new wire command subtype.

`StormglassGameplayCommandHandler` routing becomes source-aware for `CombatAction` when `activeCombat != null`:

- if `worldFlags["quest.boss.active"]` is present -> `QuestBossCoordinator.submitAction(...)`;
- otherwise -> existing `ArcCombatCoordinator.submitAction(...)`.

This keeps Android, solo and LAN clients on the same already-versioned gameplay command protocol.

## Boss scaling

Keep scaling deliberately simple and bounded. The factory derives a base tier from rarity and may add a small deterministic pressure adjustment from existing party progression/bounty, but rarity is the dominant input.

Baseline targets:

| Rarity | HP baseline | Attack baseline |
| --- | ---: | ---: |
| COMMON | 72 | 11 |
| RARE | 108 | 14 |
| EPIC | 150 | 18 |
| LEGENDARY | 200 | 22 |

Hard caps prevent generated quest bosses from exceeding the current combat engine's practical range. Bosses inherit no free debuffs or hidden player healing.

The same quest id, world state inputs and campaign seed must always produce the same initial boss state.

## Error handling and invariants

- `START_BOSS` on a non-BOSS quest: reject with no mutation.
- `START_BOSS` for an offered/resolved/foreign-island quest: reject with no mutation.
- `START_BOSS` during active combat/voyage: reject with no mutation.
- manual `PROGRESS` on a BOSS quest: reject with no mutation.
- combat command without a valid bound quest while `quest.boss.active` is set: reject rather than silently falling back to arc combat.
- duplicate command ids remain idempotent through the existing event history.
- turn-in remains the only operation that grants quest rewards.

## Android presentation

Reuse the existing `QUESTS` + `GameplayScreen` integration.

For active BOSS contracts:

- show target, rarity and reward normally;
- while progress is 0 and no combat is active, expose `START_BOSS`;
- do not expose the generic `PROGRESS` action;
- after victory, expose existing `TURN_IN` behavior;
- while combat is active, normal combat presentation takes precedence automatically.

No new Android screen class is required.

## Testing strategy

Use TDD in four layers.

1. `QuestBossFactoryTest`
   - deterministic identical output;
   - rarity scaling ordering and caps;
   - current party HP is carried without healing;
   - target identity is stable.

2. `QuestBossCoordinatorTest`
   - valid accepted BOSS starts combat and persists binding;
   - non-BOSS/invalid state is rejected without mutation;
   - ordinary combat actions use existing modifiers;
   - victory clears combat and advances quest to READY_TO_TURN_IN;
   - defeat permanently fails quest and grants no rewards;
   - duplicate commands remain idempotent.

3. `QuestLanIntegrationTest`
   - P2 accepts/starts a boss over the existing quest action path;
   - P1/P2 combat actions cross real TCP;
   - reconnect during quest boss combat restores the same authoritative combat/binding;
   - victory converges host/client state and canonical hash;
   - turn-in rewards once.

4. Presenter/coordinator tests
   - BOSS active contract exposes `START_BOSS` and not manual `PROGRESS`;
   - coordinator sends the existing `QuestAction("START_BOSS", questId)` path;
   - existing narrative arc boss tests remain green.

Final verification must include the full core suite and, because Android presentation changes, `:app:assembleDebug` against the current PR source.

## Compatibility

- No wire subtype change.
- No snapshot version bump solely for quest boss origin.
- Existing quest snapshots remain valid because the binding is a normal world flag.
- Existing arc boss behavior remains source-separated.
- Existing saves with no `quest.boss.active` flag behave exactly as before.
