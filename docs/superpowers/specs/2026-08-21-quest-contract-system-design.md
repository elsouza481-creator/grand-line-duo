# GRAND LINE DUO Persistent Quest & Contract System Design

## Goal

Add a permanent, sandbox-friendly quest and contract subsystem that can continuously generate, accept, progress, complete, fail, persist, and synchronize optional objectives without forcing the players through a linear campaign.

## Scope

This first complete vertical slice covers:

- deterministic quest-board generation per island/world state;
- quest types: hunt, exploration, collection, rescue, escort, investigation, and boss contracts;
- rarities: common, rare, epic, legendary;
- eligibility gates based on faction standing, bounty, world flags, and optional character profession/combat-style text;
- shared two-player quest state controlled by the host-authoritative `WorldState`;
- acceptance, progress, completion, abandonment/failure, and permanent history;
- rewards in Berries, evolution points, items, faction standing, and world flags;
- snapshot persistence and authoritative hashing through `WorldState`;
- deterministic quest proposals derived from Director context;
- LAN commands for accept/progress/turn-in/fail so either human player can submit actions while the host remains authoritative.

This slice does not add quest UI screens, NPC dialogue presentation, map markers, escort AI movement, or automatically spawn a combat encounter. Boss contracts expose a target id and can be connected to combat/boss factories by a later coordinator without changing quest persistence format.

## Architecture

The subsystem lives under `grandlineduo.game.quest` and follows the existing arc/ship/crew patterns.

### Quest model

`QuestDefinition` contains immutable generated contract data:

- stable `questId`;
- island id;
- title;
- `QuestType` and `QuestRarity`;
- issuer faction;
- objective target id and required amount;
- eligibility requirements;
- reward bundle;
- optional expiry generation.

`QuestProgress` contains mutable lifecycle data:

- copied definition;
- `QuestStatus` (`OFFERED`, `ACTIVE`, `READY_TO_TURN_IN`, `COMPLETED`, `FAILED`);
- progress amount;
- accepting player id;
- failure reason.

`QuestBoardState` is shared campaign state:

- deterministic generation index;
- current offered quests;
- active quests;
- completed quest ids;
- failed quest ids.

The board is stored directly on `WorldState`, making host replica replacement, LAN snapshots, reconnect, hashing, and durable persistence use the same authoritative state path as arcs, combat, crew, and ships.

### Quest engine

`QuestEngine` is pure and deterministic. It owns lifecycle invariants:

- offered quest must exist before acceptance;
- only eligible quests can be accepted;
- active quests progress up to the required amount;
- reaching the requirement changes status to `READY_TO_TURN_IN`;
- only ready quests can be completed;
- failed/completed ids cannot be reactivated;
- rewards apply once only at turn-in.

Reward application updates `WorldState` using existing domain state:

- party Berries;
- player `CharacterProfile.evolutionPoints` for both configured recipients;
- `InventoryEngine.grant` for item rewards;
- `SocialState.factionStanding` clamped to `-100..100`;
- world flags.

### Quest Director bridge

`QuestDirectorBridge` converts `WorldState` + seed + generation index + present factions into deterministic quest offers. It reuses the same world signals already used by `GrandLineDirector`: island, bounty, faction presence, social standing, crew/ship/world flags, and Director difficulty.

Generation rules:

- each refresh produces up to three offers;
- common/rare dominate low threat budgets;
- epic/legendary require higher threat budgets or strong world conditions;
- the same seed, generation index, and canonical world inputs yield the same offer ids/content;
- recently completed or failed quest ids are not re-offered.

### LAN integration

Add `GameplayWireCommand.QuestAction` with action type, quest id, and amount. `WireCodec` round-trips the command. `StormglassGameplayCommandHandler` applies quest actions only on the host and submits the resulting `WorldState` through `ReplaceWorldStateCommand`, preserving idempotency and replication semantics.

Supported actions:

- `REFRESH` — host regenerates the board deterministically;
- `ACCEPT` — accept an offered quest;
- `PROGRESS` — increment an active objective;
- `TURN_IN` — complete a ready quest and apply rewards once;
- `FAIL` — fail/abandon an active quest.

World-management restrictions match other non-combat management actions: no quest board mutation during active combat or voyage incidents.

## Persistence

Bump `WorldStateCodec` from snapshot version 9 to version 10. Version 10 writes/reads `QuestBoardState` after active combat and before players. Versions 1-9 decode with the default empty board.

The quest board participates in `CanonicalStateHasher`. A legacy world with an empty/default quest board must preserve the exact pre-v10 canonical hash by omitting the quest section when the board is empty/default; non-empty quest state must change the hash.

## Determinism and IDs

Quest ids use only stable inputs: campaign/island, seed, generation index, catalog archetype id, and deterministic slot. No wall-clock time or random UUIDs are allowed.

## Error handling

Invalid lifecycle transitions throw `IllegalArgumentException` with a stable reason. Corrupt snapshot values are rejected during decode. Quest commands are idempotent through the existing command-id fingerprint/event path.

## Testing

Add focused tests for:

1. lifecycle: accept -> progress -> ready -> turn-in;
2. eligibility rejection;
3. reward application exactly once;
4. failure history;
5. deterministic generation and rarity gating;
6. snapshot v10 round-trip and v9 compatibility;
7. authoritative hash inclusion while preserving empty-board legacy hash;
8. wire codec round-trip for quest commands;
9. LAN host/guest convergence after quest actions;
10. regression: full `bash tools/run-core-tests.sh` remains green.

## Success Criteria

The two players can receive the same deterministic island contract board, either player can submit a quest action over LAN, the host alone authoritatively mutates quest state, both replicas converge, reconnect restores the same board/progress/history, completing a quest grants rewards exactly once, and no quest requires a linear campaign path.