package grandlineduo.game.duel

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.game.arc.ArcArchetype
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcState
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyTelegraph
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.powers.HakiDiscipline
import grandlineduo.game.powers.HakiType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object DuelHandlerRoutingTest {
    fun register() {
        test("gameplay handler routes duel challenge and accept lifecycle") {
            val host = HostReplica(hubWorld("duel-handler-life"))
            val handler = StormglassGameplayCommandHandler(host, seed = 201L)

            handler.handle(GameplayWireCommand.DuelAction("duel-challenge", "p1", "CHALLENGE"), 1_000)
            assertEquals(DuelPhase.PENDING, host.state.activeDuel!!.phase)

            handler.handle(GameplayWireCommand.DuelAction("duel-accept", "p2", "ACCEPT"), 1_001)
            assertEquals(DuelPhase.ACTIVE, host.state.activeDuel!!.phase)
            assertEquals(1, host.state.activeDuel!!.round)
        }

        test("gameplay handler routes basic combat action into active duel") {
            val host = HostReplica(hubWorld("duel-handler-combat"))
            val handler = StormglassGameplayCommandHandler(host, seed = 202L)
            startDuel(handler)

            handler.handle(GameplayWireCommand.CombatAction("duel-hit", "p1", CombatActionType.ATTACK.name), 2_000)

            assertEquals(CombatActionType.ATTACK, host.state.activeDuel!!.lockedActions.getValue("p1").type)
            assertEquals(null, host.state.activeCombat)
        }

        test("gameplay handler routes prepared Haki power into active duel atomically") {
            val world = hubWorld("duel-handler-power").let { base ->
                val p1 = base.players.getValue("p1")
                val profile = p1.profile!!.copy(
                    haki = p1.profile.haki.copy(
                        disciplines = p1.profile.haki.disciplines +
                            (HakiType.BUSOSHOKU to HakiDiscipline(mastery = 1, useCount = 0))
                    )
                )
                base.copy(players = base.players + ("p1" to p1.copy(profile = profile, energy = 12, maxEnergy = 12)))
            }
            val host = HostReplica(world)
            val handler = StormglassGameplayCommandHandler(host, seed = 203L)
            startDuel(handler)

            handler.handle(GameplayWireCommand.PowerAction("duel-power", "p1", "HAKI_BUSOSHOKU"), 3_000)

            assertEquals(8, host.state.players.getValue("p1").energy)
            assertEquals(1, host.state.players.getValue("p1").profile!!.haki.disciplines.getValue(HakiType.BUSOSHOKU).useCount)
            assertEquals(CombatActionType.HAKI_BUSOSHOKU, host.state.activeDuel!!.lockedActions.getValue("p1").type)
        }

        test("pending duel rejects non duel gameplay commands") {
            val host = HostReplica(hubWorld("duel-handler-pending"))
            val handler = StormglassGameplayCommandHandler(host, seed = 204L)
            handler.handle(GameplayWireCommand.DuelAction("pending-challenge", "p1", "CHALLENGE"), 4_000)
            val before = host.state

            val result = runCatching {
                handler.handle(GameplayWireCommand.QuestAction("pending-quest", "p1", "REFRESH"), 4_001)
            }

            assertTrue(result.isFailure)
            assertEquals(before, host.state)
        }

        test("active duel rejects world management commands") {
            val host = HostReplica(hubWorld("duel-handler-exclusive"))
            val handler = StormglassGameplayCommandHandler(host, seed = 205L)
            startDuel(handler)
            val before = host.state

            val result = runCatching {
                handler.handle(GameplayWireCommand.WorldAction("duel-shop", "p1", "SHOP_BUY", "bandage", 1), 5_000)
            }

            assertTrue(result.isFailure)
            assertEquals(before, host.state)
        }

        test("simultaneous duel and pve combat state is rejected before routing") {
            val base = hubWorld("duel-handler-invalid")
            val duel = DuelState(
                duelId = "invalid-overlap",
                challengerId = "p1",
                challengedId = "p2",
                phase = DuelPhase.ACTIVE,
                round = 1,
                fighters = base.players.mapValues { (id, player) -> DuelFighter(id, player.name, player.hp, player.maxHp) },
            )
            val host = HostReplica(base.copy(activeDuel = duel, activeCombat = pveCombat()))
            val handler = StormglassGameplayCommandHandler(host, seed = 206L)
            val before = host.state

            val result = runCatching {
                handler.handle(GameplayWireCommand.CombatAction("invalid-overlap-action", "p1", "ATTACK"), 6_000)
            }

            assertTrue(result.isFailure)
            assertEquals(before, host.state)
        }

        test("unknown duel lifecycle action is rejected without mutation") {
            val host = HostReplica(hubWorld("duel-handler-unknown"))
            val handler = StormglassGameplayCommandHandler(host, seed = 207L)
            val before = host.state

            val result = runCatching {
                handler.handle(GameplayWireCommand.DuelAction("unknown-duel", "p1", "FORCE_WIN"), 7_000)
            }

            assertTrue(result.isFailure)
            assertEquals(before, host.state)
        }
    }

    private fun startDuel(handler: StormglassGameplayCommandHandler) {
        handler.handle(GameplayWireCommand.DuelAction("start-challenge", "p1", "CHALLENGE"), 100)
        handler.handle(GameplayWireCommand.DuelAction("start-accept", "p2", "ACCEPT"), 101)
    }

    private fun hubWorld(id: String): WorldState {
        val p1 = createdProfile("Kairo")
        val p2 = createdProfile("Namiya")
        return WorldState(
            campaignId = id,
            islandId = "ironwake-atoll",
            activeArc = ArcState(
                arcId = "arc-$id",
                islandId = "ironwake-atoll",
                seed = 44L,
                archetype = ArcArchetype.ISLAND_CRISIS,
                phase = ArcPhase.COMPLETE,
            ),
            players = mapOf(
                "p1" to PlayerState("p1", p1.name, p1.maxHp, p1.maxHp, 0, p1.maxEnergy, p1.maxEnergy, p1),
                "p2" to PlayerState("p2", p2.name, p2.maxHp, p2.maxHp, 0, p2.maxEnergy, p2.maxEnergy, p2),
            ),
            worldFlags = mapOf("campaign.mode" to "HOST_COOP"),
        )
    }

    private fun createdProfile(name: String) = when (
        val result = CharacterCreation.create(CharacterCreationTest.validDraft().copy(name = name))
    ) {
        is CharacterCreationResult.Success -> result.profile
        is CharacterCreationResult.Invalid -> error(result.errors.joinToString())
    }

    private fun pveCombat() = CombatState(
        round = 1,
        players = mapOf(
            "p1" to Combatant("p1", "Kairo", 30, 30),
            "p2" to Combatant("p2", "Namiya", 30, 30),
        ),
        enemy = EnemyCombatant("pve", "PvE", 50, 50, 10),
        telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
        status = CombatStatus.ACTIVE,
    )
}
