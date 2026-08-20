# Free-Roam Exploration Combat Design

## Goal

Add physical hostile encounters to island exploration without pretending they are narrative arc bosses and without introducing a second combat rules engine.

## Architecture

`WorldState.activeCombat` remains the single authoritative persisted combat state. Narrative boss combat is identified by `activeArc != null`; free-roam combat is identified by an exploration encounter flag in `worldFlags` while `activeArc == null`.

The existing `CombatEngine`, `CombatModifierResolver`, power techniques, snapshots, canonical hashing, LAN event replication, reconnect and combat presentation remain the shared combat foundation. A focused `ExplorationCombatEngine` owns only exploration-specific concerns: deterministic enemy generation, encounter activation, encounter seed, victory flag and reward.

## Physical world model

Each deterministic `ExplorationMap` exposes one `ExplorationEnemy` on a guaranteed walkable road tile that does not overlap spawn, service interactions, NPCs, quest objectives or free loot caches. The enemy contains an id, name, position, hp, attack power and reward.

The enemy is visible until its global defeated flag is present. Moving onto its tile through authoritative `EXPLORE_MOVE` starts combat automatically. Direct client input cannot choose enemy hp, attack, seed or rewards.

## Combat lifecycle

1. A player moves one cardinal tile onto a live enemy tile.
2. Host movement succeeds and `ExplorationCombatEngine.startIfEncountered` creates `activeCombat` from the party's current hp.
3. While `activeCombat` is active, ordinary `CombatAction` and power actions are resolved by the existing `CombatEngine` and character modifiers.
4. If the enemy survives, the updated combat round remains in `activeCombat`.
5. On victory, player hp is synchronized back to `WorldState.players`, `activeCombat` is cleared, the encounter is marked defeated, and the fixed authoritative reward is granted exactly once.
6. On defeat, `activeCombat` remains in `DEFEAT`; existing hardcore game-over presentation applies.

## Routing

`StormglassGameplayCommandHandler` routes basic active combat by source:

- `activeArc != null`: existing `ArcCombatCoordinator`.
- `activeArc == null` plus valid exploration encounter flag: new `ExplorationCombatCoordinator`.

Power actions keep using `PowerTechniqueEngine.prepare`, but active-combat resolution becomes source-aware so free-roam powers no longer require an active arc.

## Persistence and LAN

No snapshot schema bump is required. `activeCombat` is already serialized and hashed; `worldFlags` are already serialized, hashed, journaled and replicated. Encounter activation, locked actions, victory flags and rewards therefore inherit crash recovery and reconnect behavior.

## Presentation

`ExplorationPresentation` adds `visibleEnemies`. Android renders live hostile encounters with a red `X`. When combat starts, existing combat UI takes over. When victory clears combat, the defeated enemy no longer appears on the map.

## Testing

TDD coverage must prove:

- deterministic walkable enemy placement without overlap;
- stepping onto the tile starts free-roam combat with authoritative stats;
- normal movement elsewhere does not start combat;
- P1/P2 basic actions resolve through host authority and victory removes enemy/rewards once;
- P2 action over real TCP converges with host;
- power action works in free-roam combat without an active arc;
- active free-roam combat survives snapshot/reconnect through existing state infrastructure;
- presenter only shows undefeated enemies and Android build succeeds.
