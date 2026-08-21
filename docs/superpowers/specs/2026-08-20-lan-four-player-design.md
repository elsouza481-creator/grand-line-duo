# GRAND LINE DUO — LAN 4 Players Design

## Goal
Expand the current authoritative P1/P2 LAN model to support one host plus three remote human players (p2, p3, p4) without replacing the existing event, hash, snapshot, idempotency, or gameplay command pipeline.

## Approved architecture
P1 remains the single authoritative host. Remote peers use fixed human slots p2, p3, and p4. The host may keep three authenticated TCP sessions alive simultaneously. A reconnect for one peer replaces only that peer's existing socket and never disconnects the other peers.

The migration is incremental. The first milestone changes transport/session capacity only. Gameplay systems may continue to assume two active participants until later milestones explicitly generalize them. This keeps the current P1/P2 game playable while the network foundation grows underneath it.

## Protocol v6
`PROTOCOL_VERSION` becomes 6 because room capacity and multi-peer authentication change compatibility expectations.

The discovery advertisement gains:
- `currentPlayers: Int`
- `maxPlayers: Int = 4`

Both values are covered by the discovery checksum. `currentPlayers` counts the host plus authenticated remote peers and is bounded to 1..4.

The initial milestone keeps `ReconnectHello.peerId` as the requested slot identifier. Valid remote IDs are exactly `p2`, `p3`, and `p4`. Automatic slot allocation is a later coordinator/lobby milestone; transport tests use explicit peer IDs so the core server can be proven independently.

## Multi-peer host server
`LanHostServer` replaces the single `activeClient` with a map keyed by peer ID.

Public inspection:
- `activeClientIds: Set<String>`
- `activeClientCount: Int`
- existing `hasActiveClient` remains as compatibility shorthand for `activeClientCount > 0`

Authentication rules:
- allowed remote IDs default to `setOf("p2", "p3", "p4")`.
- any other peer is rejected with `PEER_NOT_ALLOWED`.
- a peer may only submit commands whose `actorId` equals its authenticated peer ID.
- reconnecting the same peer closes/replaces only that peer's old socket.
- p2, p3, and p4 can remain connected concurrently.

The authenticated-session idle behavior remains unchanged: handshake timeout is removed after Sync (`soTimeout = 0`) while each client keeps bounded response timeouts.

## Replication and reconnect
Every client keeps its own `ClientReplica`. All clients receive the same authoritative event stream through request/refresh. Existing `HostReplica.planReconnect()` remains unchanged because it already plans from `(lastEventId, stateHash)` rather than from a hard-coded P2 identity.

Milestone success requires:
1. p2, p3, p4 connect simultaneously over real TCP loopback.
2. all three remain active at the same time.
3. an authoritative host event is visible to all three after refresh.
4. disconnecting/reconnecting p3 does not drop p2 or p4.
5. a command sent through one authenticated socket cannot impersonate another actor.

## World model
No snapshot schema change is needed for player storage: `WorldState.players` is already `Map<String, PlayerState>`. p3/p4 player creation and gameplay participation are separate later milestones.

## Later milestones
1. Coordinator/lobby allocates available slots and advertises room occupancy.
2. Initial co-op world contains p1..p4 placeholders for host mode while solo stays p1+p2 AI.
3. Character creation/exploration accepts any present human slot.
4. PvE combat and voyage action collection resolve over dynamic participant sets.
5. quests, rewards, social/progression loops generalize from pair assumptions.
6. PvP challenge chooses a target rather than relying on `other(p1/p2)`.
7. Android lobby/HUD shows occupancy and remote slots.

## Compatibility constraints
- Keep current event journal/hash/idempotency semantics.
- Do not silently convert old protocol-v5 sessions; incompatible peers are rejected.
- Preserve all existing P1/P2 tests.
- Do not claim gameplay supports four active players until participant-dependent systems are generalized and tested.
- Each milestone must finish with Core CI and Android source build green before the next behavior is introduced.
