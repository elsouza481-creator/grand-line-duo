package grandlineduo.game.duel

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.HostReplica
import grandlineduo.game.InventoryEngine
import grandlineduo.game.StormglassPersistenceAdapter
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
import grandlineduo.game.powers.HakiDiscipline
import grandlineduo.game.powers.HakiType
import grandlineduo.game.powers.PowerTechniqueEngine
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.scenario.ScenarioState
import grandlineduo.game.ship.VoyageEncounter
import grandlineduo.game.ship.VoyageIncident
import grandlineduo.game.ship.VoyageIncidentType
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object DuelCoordinatorTest {
    fun register() {
        test("p1 can challenge p2 and p2 can challenge p1") {
            listOf("p1", "p2").forEachIndexed { index, challenger ->
                val host = HostReplica(hubWorld("duel-challenge-$index"))
                val coordinator = DuelCoordinator(host, campaignSeed = 91L)

                val event = coordinator.challenge("challenge-$index", challenger, 1_000L + index.toLong())
                val duel = host.state.activeDuel!!

                assertEquals(DuelPhase.PENDING, duel.phase)
                assertEquals(challenger, duel.challengerId)
                assertEquals(if (challenger == "p1") "p2" else "p1", duel.challengedId)
                assertTrue(duel.fighters.isEmpty())
                assertEquals(duel.duelId, event.payload["meta.duelId"])
                assertEquals(DuelPhase.PENDING.name, event.payload["meta.duelPhase"])
            }
        }

        test("solo campaign cannot forge a pvp challenge") {
            val base = hubWorld("duel-solo")
            val initial = base.copy(worldFlags = base.worldFlags + ("campaign.mode" to "SOLO"))
            val host = HostReplica(initial)
            val coordinator = DuelCoordinator(host, campaignSeed = 92L)

            val result = runCatching { coordinator.challenge("solo-challenge", "p1", 2_000) }

            assertTrue(result.isFailure)
            assertEquals(null, host.state.activeDuel)
        }

        test("challenge requires both created living characters") {
            val missing = hubWorld("duel-missing").let { it.copy(players = it.players + ("p2" to it.players.getValue("p2").copy(profile = null))) }
            val missingHost = HostReplica(missing)
            assertTrue(runCatching { DuelCoordinator(missingHost, 93L).challenge("missing", "p1", 3_000) }.isFailure)

            val zero = hubWorld("duel-zero").let { it.copy(players = it.players + ("p2" to it.players.getValue("p2").copy(hp = 0))) }
            val zeroHost = HostReplica(zero)
            assertTrue(runCatching { DuelCoordinator(zeroHost, 93L).challenge("zero", "p1", 3_001) }.isFailure)
        }

        test("only challenged player may accept or decline") {
            val acceptHost = HostReplica(hubWorld("duel-wrong-accept"))
            val acceptCoordinator = DuelCoordinator(acceptHost, 94L)
            acceptCoordinator.challenge("wrong-accept-challenge", "p1", 4_000)
            assertTrue(runCatching { acceptCoordinator.accept("wrong-accept", "p1", 4_001) }.isFailure)
            assertEquals(DuelPhase.PENDING, acceptHost.state.activeDuel!!.phase)

            val declineHost = HostReplica(hubWorld("duel-wrong-decline"))
            val declineCoordinator = DuelCoordinator(declineHost, 94L)
            declineCoordinator.challenge("wrong-decline-challenge", "p2", 4_100)
            assertTrue(runCatching { declineCoordinator.decline("wrong-decline", "p2", 4_101) }.isFailure)
            assertEquals(DuelPhase.PENDING, declineHost.state.activeDuel!!.phase)
        }

        test("accept copies current hp exactly without healing") {
            val world = hubWorld("duel-hp-copy").let {
                it.copy(players = it.players + mapOf(
                    "p1" to it.players.getValue("p1").copy(hp = 17, maxHp = 30),
                    "p2" to it.players.getValue("p2").copy(hp = 11, maxHp = 30),
                ))
            }
            val host = HostReplica(world)
            val coordinator = DuelCoordinator(host, 95L)
            coordinator.challenge("hp-challenge", "p1", 5_000)
            coordinator.accept("hp-accept", "p2", 5_001)

            val duel = host.state.activeDuel!!
            assertEquals(DuelPhase.ACTIVE, duel.phase)
            assertEquals(1, duel.round)
            assertEquals(17, duel.fighters.getValue("p1").hp)
            assertEquals(11, duel.fighters.getValue("p2").hp)
            assertEquals(17, host.state.players.getValue("p1").hp)
            assertEquals(11, host.state.players.getValue("p2").hp)
        }

        test("challenge is rejected outside a hub compatible state") {
            val cases = listOf(
                hubWorld("duel-active-combat").copy(activeCombat = pveCombat()),
                StormglassPersistenceAdapter.encode(hubWorld("duel-legacy-combat"), ScenarioState(stage = ScenarioStage.COMPLETE), pveCombat()),
                hubWorld("duel-voyage").copy(activeVoyage = VoyageEncounter(VoyageIncident(VoyageIncidentType.STORM, 1, 1L))),
                hubWorld("duel-arc-active").copy(activeArc = completeArc("duel-arc-active").copy(phase = ArcPhase.ESCALATION)),
                hubWorld("duel-story-active").copy(activeArc = null, worldFlags = mapOf("campaign.mode" to "HOST_COOP", "sg.stage" to ScenarioStage.INVESTIGATION.name)),
                hubWorld("duel-existing").copy(activeDuel = DuelState("existing", "p1", "p2", DuelPhase.PENDING)),
            )

            cases.forEachIndexed { index, world ->
                val host = HostReplica(world)
                val result = runCatching {
                    DuelCoordinator(host, 96L).challenge("blocked-$index", "p1", 6_000L + index.toLong())
                }
                assertTrue(result.isFailure, "case $index should reject duel challenge")
            }
        }

        test("decline clears pending duel without changing resources") {
            val initial = hubWorld("duel-decline").copy(partyBerries = 7_777)
            val host = HostReplica(initial)
            val coordinator = DuelCoordinator(host, 97L)
            coordinator.challenge("decline-challenge", "p1", 7_000)
            val beforeDecline = host.state
            coordinator.decline("decline", "p2", 7_001)

            assertEquals(null, host.state.activeDuel)
            assertEquals(beforeDecline.partyBerries, host.state.partyBerries)
            assertEquals(beforeDecline.players, host.state.players)
            assertEquals(beforeDecline.questBoard, host.state.questBoard)
            assertEquals(beforeDecline.socialState, host.state.socialState)
        }

        test("first duel action locks and second action resolves the round") {
            val host = HostReplica(activeDuelWorld("duel-round", 98L))
            val coordinator = DuelCoordinator(host, 98L)

            val first = coordinator.submitAction("round-p1", "p1", CombatActionType.ATTACK, 8_000)
            assertEquals(1, host.state.activeDuel!!.lockedActions.size)
            assertEquals("false", first.payload["meta.duelResolved"])

            val second = coordinator.submitAction("round-p2", "p2", CombatActionType.DEFEND, 8_001)
            assertTrue(host.state.activeDuel!!.round >= 2 || host.state.activeDuel!!.phase == DuelPhase.FINISHED)
            assertEquals("true", second.payload["meta.duelResolved"])
        }

        test("duel command retry is idempotent") {
            val host = HostReplica(activeDuelWorld("duel-retry", 99L))
            val coordinator = DuelCoordinator(host, 99L)

            val first = coordinator.submitAction("duel-lock", "p1", CombatActionType.SETUP, 9_000)
            val retry = coordinator.submitAction("duel-lock", "p1", CombatActionType.SETUP, 9_001)

            assertEquals(first.eventId, retry.eventId)
            assertEquals(1, host.state.activeDuel!!.lockedActions.size)
        }

        test("duel uses authoritative equipment modifiers") {
            fun damage(world: WorldState): Int {
                val host = HostReplica(world)
                val coordinator = DuelCoordinator(host, 100L)
                coordinator.challenge("same-challenge", "p1", 10_000)
                coordinator.accept("same-accept", "p2", 10_001)
                val beforeHp = host.state.players.getValue("p2").hp
                coordinator.submitAction("same-p1", "p1", CombatActionType.ATTACK, 10_002)
                coordinator.submitAction("same-p2", "p2", CombatActionType.DEFEND, 10_003)
                return beforeHp - host.state.players.getValue("p2").hp
            }

            val base = hubWorld("duel-loadout")
            var equipped = InventoryEngine.grant(base, "p1", "iron_sabre", 1)
            equipped = InventoryEngine.equip(equipped, "p1", "iron_sabre")

            assertTrue(damage(equipped) > damage(base))
        }

        test("prepared power spends energy and records mastery use exactly once") {
            val base = hubWorld("duel-power").let { world ->
                val player = world.players.getValue("p1")
                val profile = player.profile!!.copy(
                    haki = player.profile.haki.copy(
                        disciplines = player.profile.haki.disciplines + (HakiType.BUSOSHOKU to HakiDiscipline(1, 0))
                    )
                )
                world.copy(players = world.players + ("p1" to player.copy(profile = profile, energy = 12, maxEnergy = 12)))
            }
            val host = HostReplica(base)
            val coordinator = DuelCoordinator(host, 101L)
            coordinator.challenge("power-challenge", "p1", 11_000)
            coordinator.accept("power-accept", "p2", 11_001)
            val prepared = PowerTechniqueEngine.prepare(host.state, "p1", "HAKI_BUSOSHOKU")
            val metadata = mutableMapOf("meta.powerTechnique" to "HAKI_BUSOSHOKU")

            val first = coordinator.submitPreparedAction(
                "power-lock", "p1", prepared.combatAction, prepared.world,
                "power-action|p1|HAKI_BUSOSHOKU", metadata, 11_002,
            )
            val retry = coordinator.submitPreparedAction(
                "power-lock", "p1", prepared.combatAction, prepared.world,
                "power-action|p1|HAKI_BUSOSHOKU", metadata, 11_003,
            )

            assertEquals(first.eventId, retry.eventId)
            assertEquals(8, host.state.players.getValue("p1").energy)
            assertEquals(1, host.state.players.getValue("p1").profile!!.haki.disciplines.getValue(HakiType.BUSOSHOKU).useCount)
        }

        test("duel knockout syncs world hp without granting rewards") {
            val base = hubWorld("duel-ko").let { world ->
                world.copy(
                    partyBerries = 4_321,
                    players = world.players + ("p2" to world.players.getValue("p2").copy(hp = 4, maxHp = 30, bounty = 9_999L)),
                )
            }
            val host = HostReplica(base)
            val coordinator = DuelCoordinator(host, 102L)
            coordinator.challenge("ko-challenge", "p1", 12_000)
            coordinator.accept("ko-accept", "p2", 12_001)
            coordinator.submitAction("ko-p1", "p1", CombatActionType.FINISHER, 12_002)
            coordinator.submitAction("ko-p2", "p2", CombatActionType.DEFEND, 12_003)

            assertEquals(DuelPhase.FINISHED, host.state.activeDuel!!.phase)
            assertEquals(DuelFinishReason.KNOCKOUT, host.state.activeDuel!!.finishReason)
            assertEquals(1, host.state.players.getValue("p2").hp)
            assertEquals(4_321L, host.state.partyBerries)
            assertEquals(9_999L, host.state.players.getValue("p2").bounty)
            assertTrue(host.state.questBoard.completedQuestIds.isEmpty())
        }

        test("close works only after finish and never heals or refunds") {
            val activeHost = HostReplica(activeDuelWorld("duel-close-active", 103L))
            val activeCoordinator = DuelCoordinator(activeHost, 103L)
            assertTrue(runCatching { activeCoordinator.close("close-active", "p1", 13_000) }.isFailure)

            val base = hubWorld("duel-close").let { world ->
                world.copy(players = world.players + ("p2" to world.players.getValue("p2").copy(hp = 4, maxHp = 30)))
            }
            val host = HostReplica(base)
            val coordinator = DuelCoordinator(host, 104L)
            coordinator.challenge("close-challenge", "p1", 13_100)
            coordinator.accept("close-accept", "p2", 13_101)
            coordinator.submitAction("close-p1", "p1", CombatActionType.FINISHER, 13_102)
            coordinator.submitAction("close-p2", "p2", CombatActionType.DEFEND, 13_103)
            val hpBefore = host.state.players.mapValues { it.value.hp }
            val energyBefore = host.state.players.mapValues { it.value.energy }

            coordinator.close("close-finished", "p2", 13_104)

            assertEquals(null, host.state.activeDuel)
            assertEquals(hpBefore, host.state.players.mapValues { it.value.hp })
            assertEquals(energyBefore, host.state.players.mapValues { it.value.energy })
        }
    }

    private fun activeDuelWorld(id: String, seed: Long): WorldState {
        val host = HostReplica(hubWorld(id))
        val coordinator = DuelCoordinator(host, seed)
        coordinator.challenge("activate-challenge", "p1", 100)
        coordinator.accept("activate-accept", "p2", 101)
        return host.state
    }

    private fun hubWorld(id: String): WorldState {
        val p1Profile = createdProfile("Kairo")
        val p2Profile = createdProfile("Namiya")
        return WorldState(
            campaignId = id,
            islandId = "ironwake-atoll",
            partyBerries = 2_000,
            activeArc = completeArc(id),
            players = mapOf(
                "p1" to PlayerState("p1", p1Profile.name, p1Profile.maxHp, p1Profile.maxHp, 1_000L, p1Profile.maxEnergy, p1Profile.maxEnergy, p1Profile),
                "p2" to PlayerState("p2", p2Profile.name, p2Profile.maxHp, p2Profile.maxHp, 2_000L, p2Profile.maxEnergy, p2Profile.maxEnergy, p2Profile),
            ),
            worldFlags = mapOf("campaign.mode" to "HOST_COOP"),
        )
    }

    private fun completeArc(id: String) = ArcState(
        arcId = "arc-$id",
        islandId = "ironwake-atoll",
        seed = 55L,
        archetype = ArcArchetype.ISLAND_CRISIS,
        phase = ArcPhase.COMPLETE,
    )

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
        enemy = EnemyCombatant("dummy", "Dummy", 20, 20, 5),
        telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
        status = CombatStatus.ACTIVE,
    )
}
