package grandlineduo.appshell

import grandlineduo.core.network.LanDiscoveryListener
import grandlineduo.game.character.Attribute
import grandlineduo.game.character.CharacterDraft
import grandlineduo.game.character.Skill
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.ship.VoyageAction
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.world.ExplorationDirection
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object GameSessionCoordinatorTest {
    fun register() {
        test("single player creates AI companion and advances both-player narrative") {
            val root = Files.createTempDirectory("gld-solo")
            GameSessionCoordinator(root).use { session ->
                session.startSolo(campaignId = "solo-1")
                session.createCharacter(validDraft("Arlen"))
                val world = session.worldState()
                assertEquals("Arlen", world.players.getValue("p1").name)
                assertTrue(world.players.getValue("p2").profile != null)
                assertTrue(world.worldFlags["campaign.mode"] == "SOLO")

                session.submitScenarioChoice("help_dockworker")
                val restored = StormglassPersistenceAdapter.decode(session.worldState())
                assertEquals(ScenarioStage.INVESTIGATION, restored.scenario.stage)
            }
        }

        test("new host coop waits for human P2 instead of creating AI") {
            val root = Files.createTempDirectory("gld-host")
            GameSessionCoordinator(root).use { session ->
                session.startHost("Test Host", campaignId = "coop-1")
                session.createCharacter(validDraft("Mira"))
                assertEquals(null, session.worldState().players.getValue("p2").profile)
                assertEquals(SessionMode.HOST_COOP, session.mode)
                assertTrue(session.boundPort > 0)
            }
        }

        test("host assigns p2 p3 p4 to joining coordinators and advertises live four player occupancy") {
            val host = GameSessionCoordinator()
            val p2 = GameSessionCoordinator()
            val p3 = GameSessionCoordinator()
            val p4 = GameSessionCoordinator()
            try {
                host.startHost("Four Player Host", campaignId = "coord-four-player")
                joinViaDiscovery(host, p2, freeUdpPort())
                joinViaDiscovery(host, p3, freeUdpPort())
                joinViaDiscovery(host, p4, freeUdpPort())

                assertEquals("p2", p2.actorId)
                assertEquals("p3", p3.actorId)
                assertEquals("p4", p4.actorId)

                val discoveryPort = freeUdpPort()
                LanDiscoveryListener(bindAddress = "127.0.0.1", port = discoveryPort).use { listener ->
                    listener.start()
                    host.advertiseOnce(InetAddress.getByName("127.0.0.1"), discoveryPort)
                    val ad = listener.receive(1_000)?.advertisement ?: error("host advertisement not received")
                    assertEquals(4, ad.currentPlayers)
                    assertEquals(4, ad.maxPlayers)
                }
            } finally {
                p4.close()
                p3.close()
                p2.close()
                host.close()
            }
        }

        test("P3 and P4 create authoritative characters over assigned LAN slots and all replicas converge") {
            val host = GameSessionCoordinator()
            val p2 = GameSessionCoordinator()
            val p3 = GameSessionCoordinator()
            val p4 = GameSessionCoordinator()
            try {
                host.startHost("Character Host", campaignId = "coord-four-characters")
                joinViaDiscovery(host, p2, freeUdpPort())
                joinViaDiscovery(host, p3, freeUdpPort())
                joinViaDiscovery(host, p4, freeUdpPort())

                p3.createCharacter(validDraft("Rika"))
                p4.createCharacter(validDraft("Bram"))
                p2.refresh()
                p3.refresh()
                p4.refresh()

                val authoritative = host.worldState()
                assertEquals("Rika", authoritative.players.getValue("p3").name)
                assertEquals("Bram", authoritative.players.getValue("p4").name)
                assertTrue(authoritative.players.getValue("p3").profile != null)
                assertTrue(authoritative.players.getValue("p4").profile != null)
                assertEquals(authoritative, p2.worldState())
                assertEquals(authoritative, p3.worldState())
                assertEquals(authoritative, p4.worldState())
            } finally {
                p4.close()
                p3.close()
                p2.close()
                host.close()
            }
        }

        test("P3 and P4 move independently over LAN and all replicas converge") {
            val host = GameSessionCoordinator()
            val p2 = GameSessionCoordinator()
            val p3 = GameSessionCoordinator()
            val p4 = GameSessionCoordinator()
            try {
                host.startHost("Exploration Host", campaignId = "coord-four-exploration")
                joinViaDiscovery(host, p2, freeUdpPort())
                joinViaDiscovery(host, p3, freeUdpPort())
                joinViaDiscovery(host, p4, freeUdpPort())
                p3.createCharacter(validDraft("Rika"))
                p4.createCharacter(validDraft("Bram"))

                val map = ExplorationEngine.mapFor(host.worldState().campaignId, host.worldState().islandId)
                p3.submitWorldAction("EXPLORE_MOVE", "EAST", 999)
                p4.submitWorldAction("EXPLORE_MOVE", "WEST", 999)
                p2.refresh()
                p3.refresh()
                p4.refresh()

                val authoritative = host.worldState()
                assertEquals(map.spawn + ExplorationDirection.EAST, ExplorationEngine.position(authoritative, "p3"))
                assertEquals(map.spawn + ExplorationDirection.WEST, ExplorationEngine.position(authoritative, "p4"))
                assertTrue(ExplorationEngine.position(authoritative, "p3") != ExplorationEngine.position(authoritative, "p4"))
                assertEquals(authoritative, p2.worldState())
                assertEquals(authoritative, p3.worldState())
                assertEquals(authoritative, p4.worldState())
            } finally {
                p4.close()
                p3.close()
                p2.close()
                host.close()
            }
        }

        test("four player host voyage waits for p1 p2 p3 p4 actions over real TCP and converges") {
            val host = GameSessionCoordinator()
            val p2 = GameSessionCoordinator()
            val p3 = GameSessionCoordinator()
            val p4 = GameSessionCoordinator()
            try {
                host.startHost("Voyage Host", campaignId = "coord-four-voyage")
                joinViaDiscovery(host, p2, freeUdpPort())
                joinViaDiscovery(host, p3, freeUdpPort())
                joinViaDiscovery(host, p4, freeUdpPort())
                host.createCharacter(validDraft("Arlen"))
                p2.createCharacter(validDraft("Mira"))
                p3.createCharacter(validDraft("Rika"))
                p4.createCharacter(validDraft("Bram"))

                completeStormglassForParty(host, p2, p3, p4)
                assertEquals(ScenarioStage.COMPLETE, StormglassPersistenceAdapter.decode(host.worldState()).scenario.stage)
                assertTrue(moveP1ToDock(host), "P1 must reach the physical dock before a four-player voyage")

                host.advanceCampaign()
                assertEquals(setOf("p1", "p2", "p3", "p4"), host.worldState().activeVoyage?.participants)

                host.submitVoyageAction(VoyageAction.HELM)
                p2.refresh()
                p2.submitVoyageAction(VoyageAction.PROTECT_SUPPLIES)
                p3.refresh()
                p3.submitVoyageAction(VoyageAction.REPAIR)
                assertTrue(host.worldState().activeVoyage != null, "Voyage must wait for p4")
                p4.refresh()
                p4.submitVoyageAction(VoyageAction.LOOKOUT)

                p2.refresh()
                p3.refresh()
                p4.refresh()
                val authoritative = host.worldState()
                assertEquals(null, authoritative.activeVoyage)
                assertEquals(authoritative, p2.worldState())
                assertEquals(authoritative, p3.worldState())
                assertEquals(authoritative, p4.worldState())
            } finally {
                p4.close()
                p3.close()
                p2.close()
                host.close()
            }
        }

        test("session coordinator exposes authoritative world management actions") {
            val root = Files.createTempDirectory("gld-world-action")
            GameSessionCoordinator(root).use { session ->
                session.startSolo(campaignId = "world-action")
                session.createCharacter(validDraft("Mira"))
                session.submitWorldAction("EXPLORE_MOVE", "WEST", 999)
                session.submitWorldAction("EXPLORE_MOVE", "WEST", 999)
                session.submitWorldAction("EXPLORE_MOVE", "NORTH", 999)

                val before = session.worldState().partyBerries
                session.submitWorldAction("SHOP_BUY", "bandage", 1)
                assertEquals(before - 250L, session.worldState().partyBerries)
                assertTrue(grandlineduo.game.InventoryEngine.read(session.worldState(), "p1").items.getValue("bandage") >= 3)
            }
        }

        test("single player save can be resumed with identical state") {
            val root = Files.createTempDirectory("gld-resume")
            val expected = GameSessionCoordinator(root).use { first ->
                first.startSolo(campaignId = "resume-1")
                first.createCharacter(validDraft("Kael"))
                first.submitScenarioChoice("visit_tavern")
                first.worldState()
            }
            GameSessionCoordinator(root).use { second ->
                second.resume("resume-1")
                assertEquals(expected, second.worldState())
                assertEquals(SessionMode.SOLO, second.mode)
            }
        }
    }

    private fun completeStormglassForParty(
        host: GameSessionCoordinator,
        p2: GameSessionCoordinator,
        p3: GameSessionCoordinator,
        p4: GameSessionCoordinator,
    ) {
        val remotes = mapOf("p2" to p2, "p3" to p3, "p4" to p4)
        var guard = 0
        while (StormglassPersistenceAdapter.decode(host.worldState()).scenario.stage != ScenarioStage.COMPLETE && guard++ < 120) {
            val restored = StormglassPersistenceAdapter.decode(host.worldState())
            val combat = restored.combat
            if (combat != null) {
                if ("p1" !in combat.lockedActions && (combat.players["p1"]?.hp ?: 0) > 0) {
                    host.submitCombatAction(CombatActionType.SETUP)
                }
                remotes.forEach { (playerId, client) ->
                    client.refresh()
                    val current = StormglassPersistenceAdapter.decode(host.worldState()).combat ?: return@forEach
                    if (playerId !in current.lockedActions && (current.players[playerId]?.hp ?: 0) > 0) {
                        val action = if (playerId == "p2") CombatActionType.FINISHER else CombatActionType.ATTACK
                        client.submitCombatAction(action)
                    }
                }
                continue
            }

            val p1View = GamePresenter.present(host.worldState(), "p1")
            if (p1View.screen == GameScreen.STORY && p1View.actions.isNotEmpty()) {
                host.submitScenarioChoice(p1View.actions.first().id)
            }
            remotes.forEach { (playerId, client) ->
                client.refresh()
                val view = GamePresenter.present(client.worldState(), playerId)
                if (view.screen == GameScreen.STORY && view.actions.isNotEmpty()) {
                    client.submitScenarioChoice(view.actions.first().id)
                }
            }
        }
        assertTrue(guard < 120, "Four-player host must finish Stormglass with every participant decision")
    }

    private fun moveP1ToDock(host: GameSessionCoordinator): Boolean {
        var guard = 0
        while (ExplorationEngine.interactionAt(host.worldState(), "p1") != ExplorationInteraction.DOCK && guard++ < 40) {
            if (host.worldState().activeCombat != null) return false
            val world = host.worldState()
            val map = ExplorationEngine.mapFor(world.campaignId, world.islandId)
            val current = ExplorationEngine.position(world, "p1")
            val dock = map.interactions.entries.first { it.value == ExplorationInteraction.DOCK }.key
            val direction = when {
                current.x < dock.x -> ExplorationDirection.EAST
                current.x > dock.x -> ExplorationDirection.WEST
                current.y < dock.y -> ExplorationDirection.SOUTH
                else -> ExplorationDirection.NORTH
            }
            host.submitWorldAction("EXPLORE_MOVE", direction.name, 999)
            if (host.worldState().activeCombat != null) return false
        }
        assertTrue(guard < 40, "P1 must be able to walk to the physical dock")
        return true
    }

    private fun joinViaDiscovery(host: GameSessionCoordinator, client: GameSessionCoordinator, discoveryPort: Int) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                client.discoverAndJoin(
                    timeoutMillis = 2_000,
                    bindAddress = "127.0.0.1",
                    discoveryPort = discoveryPort,
                )
            }
            Thread.sleep(100)
            host.advertiseOnce(InetAddress.getByName("127.0.0.1"), discoveryPort)
            future.get(3, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    fun validDraft(name: String): CharacterDraft = CharacterDraft(
        name = name,
        age = 22,
        origin = "North Blue",
        appearance = "hair=black;skin=medium;outfit=navy;accessory=none;color=red",
        personality = "Teimoso mas leal",
        dream = "Mapear uma rota impossível",
        fear = "Perder a tripulação",
        profession = "Aventureiro",
        combatStyle = "Espadachim",
        background = "Criado em um porto comercial",
        motivation = "Liberdade",
        pirateRelation = "Desconfiado",
        marineRelation = "Cauteloso",
        importantPerson = "Mentor do porto",
        defect = "Impulsivo",
        attributes = mapOf(
            Attribute.FOR to 2, Attribute.DES to 2, Attribute.CON to 2,
            Attribute.INT to 1, Attribute.PER to 1, Attribute.CAR to 1, Attribute.VON to 1,
        ),
        skills = mapOf(
            Skill.BLADED_WEAPONS to 2, Skill.ATHLETICS to 1, Skill.ACROBATICS to 1,
            Skill.PERCEPTION to 1, Skill.NAVIGATION to 1, Skill.SURVIVAL to 1, Skill.PERSUASION to 1,
        ),
    )
}
