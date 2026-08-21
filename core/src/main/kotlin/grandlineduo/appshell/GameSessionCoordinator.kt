package grandlineduo.appshell

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanDiscoveryAdvertisement
import grandlineduo.core.network.LanDiscoveryAdvertiser
import grandlineduo.core.network.LanDiscoveryListener
import grandlineduo.core.network.LanHostServer
import grandlineduo.core.network.PROTOCOL_VERSION
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.arc.ArcEngine
import grandlineduo.game.arc.ArcCoordinator
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcStartContext
import grandlineduo.game.character.Attribute
import grandlineduo.game.character.CharacterDraft
import grandlineduo.game.character.Skill
import grandlineduo.game.character.ProgressionEngine
import grandlineduo.game.powers.PowerDiscoveryEngine
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.network.StormglassGameplayCommandHandler
import grandlineduo.game.scenario.StormglassCayScenario
import grandlineduo.game.ship.ShipEngine
import grandlineduo.game.ship.VoyageAction
import grandlineduo.game.ship.VoyageEncounter
import grandlineduo.game.ship.VoyageIncident
import grandlineduo.game.ship.VoyageIncidentType
import java.io.Closeable
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

enum class SessionMode { NONE, SOLO, HOST_COOP, CLIENT_COOP }

/**
 * One session API for both single-player and LAN co-op. P1 is always authoritative.
 * In solo, P2 uses a deterministic companion planner through the exact same gameplay commands.
 */
class GameSessionCoordinator(private val saveRoot: Path? = null) : Closeable {
    var mode: SessionMode = SessionMode.NONE
        private set
    var actorId: String = "p1"
        private set
    var lastError: String? = null
        private set

    private var hostReplica: HostReplica? = null
    private var handler: StormglassGameplayCommandHandler? = null
    private var durableStore: DurableCampaignStore? = null
    private var hostServer: LanHostServer? = null
    private var advertisement: LanDiscoveryAdvertisement? = null
    private var clientReplica: ClientReplica? = null
    private var clientConnection: LanClientConnection? = null

    val boundPort: Int get() = hostServer?.boundPort ?: -1
    val hasRemotePlayer: Boolean get() = hostServer?.hasActiveClient == true || mode == SessionMode.CLIENT_COOP

    @Synchronized
    fun startSolo(campaignId: String = "gld-${UUID.randomUUID()}"): WorldState {
        closeSessionResources()
        mode = SessionMode.SOLO
        actorId = "p1"
        val initial = initialWorld(campaignId, "SOLO")
        createHostRuntime(initial, recoveredEvents = emptyList(), networkHostName = null)
        return worldState()
    }

    @Synchronized
    fun startHost(hostName: String, campaignId: String = "gld-${UUID.randomUUID()}"): WorldState {
        closeSessionResources()
        mode = SessionMode.HOST_COOP
        actorId = "p1"
        val initial = initialWorld(campaignId, "HOST_COOP")
        createHostRuntime(initial, recoveredEvents = emptyList(), networkHostName = hostName)
        return worldState()
    }

    @Synchronized
    fun advertiseOnce(
        targetAddress: InetAddress = InetAddress.getByName("255.255.255.255"),
        discoveryPort: Int = 37778,
    ) {
        val ad = advertisement ?: throw IllegalStateException("No hosted co-op campaign")
        LanDiscoveryAdvertiser(targetAddress, discoveryPort).use { it.send(ad) }
    }

    @Synchronized
    fun discoverAndJoin(
        timeoutMillis: Int = 5_000,
        bindAddress: String = "0.0.0.0",
        discoveryPort: Int = 37778,
    ): WorldState {
        closeSessionResources()
        mode = SessionMode.CLIENT_COOP
        actorId = "p2"
        val discovered = LanDiscoveryListener(bindAddress, discoveryPort).use { listener ->
            listener.start()
            listener.receive(timeoutMillis)
        } ?: throw IllegalStateException("Nenhuma aventura compatível foi encontrada")
        val ad = discovered.advertisement
        val replica = ClientReplica(WorldState(campaignId = ad.campaignId))
        val connection = LanClientConnection(
            host = discovered.sourceAddress.hostAddress,
            port = ad.tcpPort,
            peerId = "p2",
            replica = replica,
        )
        connection.connect()
        clientReplica = replica
        clientConnection = connection
        return replica.state
    }

    @Synchronized
    fun refresh(): WorldState {
        if (mode == SessionMode.CLIENT_COOP) clientConnection?.refresh()
        else if (mode == SessionMode.SOLO || mode == SessionMode.HOST_COOP) postProcessHostState()
        return worldState()
    }

    @Synchronized
    fun createCharacter(draft: CharacterDraft): WorldState {
        sendGameplay(
            GameplayWireCommand.CharacterCreate(
                commandId = nextCommandId("character"),
                actorId = actorId,
                draft = draft,
            )
        )
        if (mode == SessionMode.SOLO) {
            ensureSoloCompanion()
            autoPlayCompanion()
        }
        return worldState()
    }

    @Synchronized
    fun submitScenarioChoice(choiceId: String): WorldState {
        sendGameplay(GameplayWireCommand.ScenarioChoice(nextCommandId("scenario"), actorId, choiceId))
        if (mode == SessionMode.SOLO) autoPlayCompanion()
        postProcessHostState()
        return worldState()
    }

    @Synchronized
    fun submitCombatAction(actionType: CombatActionType): WorldState {
        sendGameplay(GameplayWireCommand.CombatAction(nextCommandId("combat"), actorId, actionType.name))
        if (mode == SessionMode.SOLO) autoPlayCompanion()
        postProcessHostState()
        return worldState()
    }

    @Synchronized
    fun submitPowerAction(techniqueId: String): WorldState {
        sendGameplay(GameplayWireCommand.PowerAction(nextCommandId("power"), actorId, techniqueId))
        if (mode == SessionMode.SOLO) autoPlayCompanion()
        postProcessHostState()
        return worldState()
    }

    @Synchronized
    fun submitArcChoice(choiceId: String): WorldState {
        sendGameplay(GameplayWireCommand.ArcChoice(nextCommandId("arc"), actorId, choiceId))
        if (mode == SessionMode.SOLO) autoPlayCompanion()
        postProcessHostState()
        return worldState()
    }

    @Synchronized
    fun submitVoyageAction(action: VoyageAction): WorldState {
        sendGameplay(GameplayWireCommand.VoyageAction(nextCommandId("voyage"), actorId, action.name))
        if (mode == SessionMode.SOLO) autoPlayCompanion()
        postProcessHostState()
        return worldState()
    }

    /** Starts the next sea leg. Only authoritative P1 can change islands. */
    @Synchronized
    fun advanceCampaign(): WorldState {
        require(mode == SessionMode.SOLO || mode == SessionMode.HOST_COOP) { "Only P1 can set sail" }
        postProcessHostState()
        val world = worldState()
        require(world.activeCombat == null && StormglassPersistenceAdapter.decode(world).combat == null) { "Cannot sail during combat" }
        require(world.activeVoyage == null) { "A voyage incident is already active" }
        val scenarioComplete = StormglassPersistenceAdapter.decode(world).scenario.stage == grandlineduo.game.scenario.ScenarioStage.COMPLETE
        val arcComplete = world.activeArc?.phase == ArcPhase.COMPLETE
        require(scenarioComplete || arcComplete) { "Current chapter is not complete" }

        val chapter = world.worldFlags["campaign.chapter"]?.toIntOrNull() ?: 0
        if (chapter >= CAMPAIGN_ISLANDS.size) {
            completeCampaign()
            return worldState()
        }
        val target = CAMPAIGN_ISLANDS[chapter]
        val incidentType = VoyageIncidentType.entries[chapter % VoyageIncidentType.entries.size]
        val encounter = VoyageEncounter(
            VoyageIncident(
                type = incidentType,
                severity = (1 + chapter / 2).coerceAtMost(4),
                seed = campaignSeed(world.campaignId) xor (chapter.toLong() * 7919L),
            )
        )
        val flags = world.worldFlags + mapOf(
            "campaign.pendingIsland" to target,
            "campaign.traveling" to "true",
        )
        replaceHostWorld(
            next = world.copy(activeVoyage = encounter, worldFlags = flags),
            prefix = "campaign-sail",
            fingerprint = "campaign-sail|$chapter|$target",
            metadata = mapOf("meta.campaignSail" to target),
        )
        return worldState()
    }

    @Synchronized
    fun submitInventoryAction(action: String, target: String, amount: Int = 1): WorldState {
        sendGameplay(GameplayWireCommand.InventoryAction(nextCommandId("inventory"), actorId, action, target, amount))
        return worldState()
    }

    @Synchronized
    fun submitWorldAction(action: String, target: String = "", amount: Int = 1): WorldState {
        sendGameplay(GameplayWireCommand.WorldAction(nextCommandId("world"), actorId, action, target, amount))
        return worldState()
    }

    @Synchronized
    fun submitQuestAction(action: String, questId: String = "", amount: Int = 1): WorldState {
        sendGameplay(
            GameplayWireCommand.QuestAction(
  commandId = nextCommandId("quest"),
  actorId = actorId,
  actionType = action,
  questId = questId,
  amount = amount,
            )
        )
        return worldState()
    }

    @Synchronized
    fun worldState(): WorldState = when (mode) {
        SessionMode.SOLO, SessionMode.HOST_COOP -> hostReplica?.state
            ?: throw IllegalStateException("No host campaign")
        SessionMode.CLIENT_COOP -> clientReplica?.state
            ?: throw IllegalStateException("No client campaign")
        SessionMode.NONE -> throw IllegalStateException("No active campaign")
    }

    @Synchronized
    fun resume(campaignId: String): WorldState {
        closeSessionResources()
        val root = saveRoot ?: throw IllegalStateException("Saving is not configured")
        val dir = root.resolve(campaignId)
        val recovered = DurableCampaignStore(dir).recover()
        val storedMode = recovered.state.worldFlags["campaign.mode"] ?: "SOLO"
        mode = if (storedMode == "HOST_COOP") SessionMode.HOST_COOP else SessionMode.SOLO
        actorId = "p1"
        createHostRuntime(
            initial = recovered.state,
            recoveredEvents = recovered.events,
            networkHostName = if (mode == SessionMode.HOST_COOP) "Grand Line Host" else null,
            initializeStore = false,
        )
        return worldState()
    }

    fun savedCampaignIds(): List<String> {
        val root = saveRoot ?: return emptyList()
        if (!Files.exists(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) && Files.exists(it.resolve("campaign.snapshot")) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
    }

    private fun createHostRuntime(
        initial: WorldState,
        recoveredEvents: List<grandlineduo.core.events.CampaignEvent>,
        networkHostName: String?,
        initializeStore: Boolean = true,
    ) {
        val store = saveRoot?.resolve(initial.campaignId)?.let(::DurableCampaignStore)
        if (initializeStore) store?.initialize(initial)
        durableStore = store
        val host = HostReplica(initialState = initial, recoveredState = initial, recoveredEvents = recoveredEvents)
        hostReplica = host
        val gameplay = StormglassGameplayCommandHandler(
            hostReplica = host,
            seed = campaignSeed(initial.campaignId),
            durableStore = store,
        )
        handler = gameplay
        if (networkHostName != null) {
            val server = LanHostServer(host, gameplayCommandHandler = gameplay)
            server.start()
            hostServer = server
            advertisement = LanDiscoveryAdvertisement(
                protocolVersion = PROTOCOL_VERSION,
                sessionId = UUID.randomUUID().toString(),
                campaignId = initial.campaignId,
                hostName = networkHostName.ifBlank { "Grand Line Host" }.take(80),
                tcpPort = server.boundPort,
            )
        }
    }

    private fun sendGameplay(command: GameplayWireCommand) {
        lastError = null
        try {
            when (mode) {
                SessionMode.SOLO, SessionMode.HOST_COOP -> handler!!.handle(command, System.currentTimeMillis())
                SessionMode.CLIENT_COOP -> clientConnection!!.sendGameplay(command)
                SessionMode.NONE -> throw IllegalStateException("No active campaign")
            }
        } catch (e: Exception) {
            lastError = e.message
            throw e
        }
    }

    private fun ensureSoloCompanion() {
        val host = hostReplica ?: return
        if (host.state.players["p1"]?.profile == null || host.state.players["p2"]?.profile != null) return
        handler!!.handle(
            GameplayWireCommand.CharacterCreate(
                nextCommandId("ai-character"),
                "p2",
                soloCompanionDraft(),
            ),
            System.currentTimeMillis(),
        )
    }

    /** One deterministic P2 action whenever P1 has committed and the rules are waiting for P2. */
    private fun autoPlayCompanion() {
        val host = hostReplica ?: return
        if (host.state.players["p2"]?.profile == null) return

        val voyage = host.state.activeVoyage
        if (voyage != null && "p2" !in voyage.actions) {
            val p1 = voyage.actions["p1"]
            val chosen = when (voyage.incident.type) {
                VoyageIncidentType.STORM -> if (p1 == VoyageAction.HELM) VoyageAction.PROTECT_SUPPLIES else VoyageAction.HELM
                VoyageIncidentType.SEA_KING -> if (p1 == VoyageAction.HELM) VoyageAction.CANNONS else VoyageAction.HELM
                VoyageIncidentType.MARINE_INTERCEPTION -> if (p1 == VoyageAction.HELM) VoyageAction.LOOKOUT else VoyageAction.HELM
                VoyageIncidentType.PIRATE_AMBUSH -> if (p1 == VoyageAction.HELM) VoyageAction.CANNONS else VoyageAction.HELM
            }
            handler!!.handle(GameplayWireCommand.VoyageAction(nextCommandId("ai-voyage"), "p2", chosen.name), System.currentTimeMillis())
            return
        }

        val activeArcCombat = host.state.activeCombat
        if (activeArcCombat != null && activeArcCombat.status == grandlineduo.game.combat.CombatStatus.ACTIVE) {
            if (activeArcCombat.players["p2"]?.hp ?: 0 <= 0) return
            if ("p2" in activeArcCombat.lockedActions) return
            val p1Action = activeArcCombat.lockedActions["p1"]?.type
            val chosen = when {
                activeArcCombat.telegraph.targetPlayerId == "p2" && activeArcCombat.telegraph.type == grandlineduo.game.combat.EnemyAttackType.HEAVY_STRIKE -> CombatActionType.DODGE
                p1Action == CombatActionType.SETUP -> CombatActionType.FINISHER
                else -> CombatActionType.ATTACK
            }
            handler!!.handle(GameplayWireCommand.CombatAction(nextCommandId("ai-combat"), "p2", chosen.name), System.currentTimeMillis())
            return
        }
        if (activeArcCombat != null) return

        val restored = StormglassPersistenceAdapter.decode(host.state)
        val combat = restored.combat
        if (combat != null && combat.status == grandlineduo.game.combat.CombatStatus.ACTIVE) {
            if (combat.players["p2"]?.hp ?: 0 <= 0 || "p2" in combat.lockedActions) return
            val p1Action = combat.lockedActions["p1"]?.type
            val chosen = when {
                combat.telegraph.targetPlayerId == "p2" && combat.telegraph.type == grandlineduo.game.combat.EnemyAttackType.HEAVY_STRIKE -> CombatActionType.DODGE
                p1Action == CombatActionType.SETUP -> CombatActionType.FINISHER
                else -> CombatActionType.ATTACK
            }
            handler!!.handle(GameplayWireCommand.CombatAction(nextCommandId("ai-combat"), "p2", chosen.name), System.currentTimeMillis())
            return
        }

        val arc = host.state.activeArc
        if (arc != null && arc.phase != grandlineduo.game.arc.ArcPhase.COMPLETE && "p2" !in arc.actedThisPhase) {
            val choice = ArcEngine.view(arc, "p2").choices.firstOrNull() ?: return
            handler!!.handle(GameplayWireCommand.ArcChoice(nextCommandId("ai-arc"), "p2", choice.id), System.currentTimeMillis())
            return
        }

        val scenario = restored.scenario
        if ("p2" !in scenario.actedThisStage) {
            val choice = StormglassCayScenario().view(scenario, "p2").choices.firstOrNull() ?: return
            handler!!.handle(GameplayWireCommand.ScenarioChoice(nextCommandId("ai-scenario"), "p2", choice.id), System.currentTimeMillis())
        }
    }

    private fun postProcessHostState() {
        if (mode != SessionMode.SOLO && mode != SessionMode.HOST_COOP) return
        val host = hostReplica ?: return
        var world = host.state

        val scenario = StormglassPersistenceAdapter.decode(world).scenario
        if (scenario.stage == grandlineduo.game.scenario.ScenarioStage.COMPLETE && world.worldFlags["reward.stormglass"] != "true") {
            var rewarded = grandlineduo.game.InventoryEngine.grant(world, "p1", "stormglass_log_pose", 1)
            rewarded = grandlineduo.game.InventoryEngine.grant(rewarded, "p1", "reinforced_coat", 1)
            rewarded = grandlineduo.game.InventoryEngine.grant(rewarded, "p2", "bandage", 2)
            rewarded = awardPartyEvolutionPoints(rewarded, 2)
            rewarded = rewarded.copy(
                partyBerries = rewarded.partyBerries + 12_000L,
                worldFlags = rewarded.worldFlags + ("reward.stormglass" to "true"),
            )
            replaceHostWorld(rewarded, "reward-stormglass", "reward-stormglass|1", mapOf("meta.reward" to "STORMGLASS"))
            world = host.state
        }

        val arc = world.activeArc
        if (arc?.phase == ArcPhase.COMPLETE) {
            val rewardKey = "reward.arc.${arc.arcId}"
            if (world.worldFlags[rewardKey] != "true") {
                val chapter = world.worldFlags["campaign.chapter"]?.toIntOrNull() ?: 1
                val loot = when (chapter % 4) {
                    0 -> "marine_vest"
                    1 -> "iron_sabre"
                    2 -> "energy_tonic"
                    else -> "kairouseki_shard"
                }
                var rewarded = grandlineduo.game.InventoryEngine.grant(world, "p1", loot, 1)
                rewarded = grandlineduo.game.InventoryEngine.grant(rewarded, "p2", "ration", 2)
                rewarded = awardPartyEvolutionPoints(rewarded, 2 + chapter.coerceAtMost(3))
                var rewardFlags = rewarded.worldFlags + (rewardKey to "true")
                val fruitDiscovery = PowerDiscoveryEngine.fruitDiscovery(campaignSeed(rewarded.campaignId))
                if (chapter == fruitDiscovery.chapter && rewardFlags["fruit.discovery.id"] == null &&
                    rewarded.players.values.none { it.profile?.devilFruit != null }) {
                    rewardFlags = rewardFlags + ("fruit.discovery.id" to fruitDiscovery.definition.id)
                }
                rewarded = rewarded.copy(
                    partyBerries = rewarded.partyBerries + 15_000L + chapter * 5_000L,
                    worldFlags = rewardFlags,
                )
                replaceHostWorld(rewarded, "reward-arc", "reward-arc|${arc.arcId}", mapOf("meta.reward" to arc.arcId))
                world = host.state
            }
        }

        val pending = world.worldFlags["campaign.pendingIsland"]
        if (pending != null && world.activeVoyage == null) {
            val chapter = world.worldFlags["campaign.chapter"]?.toIntOrNull() ?: 0
            val flags = world.worldFlags.toMutableMap().also {
                it.remove("campaign.pendingIsland")
                it.remove("campaign.traveling")
                it["campaign.chapter"] = (chapter + 1).toString()
            }
            replaceHostWorld(
                world.copy(islandId = pending, activeArc = null, worldFlags = flags),
                "campaign-arrive",
                "campaign-arrive|$pending|${chapter + 1}",
                mapOf("meta.campaignArrive" to pending),
            )
            val arrived = host.state
            val context = ArcStartContext(
                seed = campaignSeed(arrived.campaignId) xor (chapter.toLong() * 104729L),
                islandId = pending,
                presentFactions = factionsFor(pending),
                worldFlags = worldFlagsForIsland(pending),
                totalBounty = arrived.players.values.sumOf { it.bounty },
            )
            ArcCoordinator(host, durableStore = durableStore).startArc(nextCommandId("arc-start"), context, System.currentTimeMillis())
        }
    }

    private fun awardPartyEvolutionPoints(world: WorldState, amount: Int): WorldState =
        world.copy(players = world.players.mapValues { (_, player) ->
            val profile = player.profile ?: return@mapValues player
            player.copy(profile = ProgressionEngine.awardEvolutionPoints(profile, amount))
        })

    private fun completeCampaign() {
        val world = hostReplica!!.state
        if (world.worldFlags["campaign.complete"] == "true") return
        val totalBounty = world.players.values.sumOf { it.bounty }
        val epilogue = when {
            totalBounty >= 100_000_000L -> "A dupla desaparece no horizonte como uma das tripulações mais procuradas de sua geração. A Marinha mantém seus cartazes em todas as rotas."
            world.socialState.factionStanding.values.any { it >= 50 } -> "As ilhas libertadas transformam seus nomes em histórias de resistência. Portos aliados continuam esperando o retorno da tripulação."
            else -> "O Log Pose finalmente estabiliza. Vocês sobreviveram à rota sem aceitar que o mundo escolhesse o destino por vocês."
        }
        replaceHostWorld(
            world.copy(worldFlags = world.worldFlags + mapOf("campaign.complete" to "true", "campaign.epilogue" to epilogue)),
            "campaign-complete",
            "campaign-complete|${world.campaignId}",
            mapOf("meta.campaignComplete" to "true"),
        )
    }

    private fun replaceHostWorld(next: WorldState, prefix: String, fingerprint: String, metadata: Map<String, String>) {
        val host = hostReplica ?: throw IllegalStateException("No authoritative host")
        val result = host.submit(
            ReplaceWorldStateCommand(
                commandId = nextCommandId(prefix),
                actorId = "gm",
                nextState = next,
                sourceFingerprint = fingerprint,
                metadata = metadata,
            ),
            System.currentTimeMillis(),
        )
        durableStore?.commit(result.event, host.state)
    }

    private fun factionsFor(islandId: String): Set<String> = when (islandId) {
        "emberwake" -> setOf("PIRATES")
        "brineveil" -> setOf("MARINES")
        "gearfall" -> setOf("UNDERWORLD")
        "hollow-crown" -> setOf("PIRATES")
        "meridian-vault" -> setOf("MARINES", "UNDERWORLD")
        else -> emptySet()
    }

    private fun worldFlagsForIsland(islandId: String): Set<String> = when (islandId) {
        "hollow-crown", "meridian-vault" -> setOf("ANCIENT_RUINS")
        else -> emptySet()
    }

    private fun initialWorld(campaignId: String, modeFlag: String): WorldState = WorldState(
        campaignId = campaignId,
        islandId = "stormglass-cay",
        partyBerries = 5_000L,
        shipState = ShipEngine.starterShip("ship-$campaignId", "Vento Livre"),
        players = mapOf(
            "p1" to PlayerState("p1", "Jogador 1", 20, 20, 0, 10, 10),
            "p2" to PlayerState("p2", if (modeFlag == "SOLO") "Companheiro" else "Jogador 2", 20, 20, 0, 10, 10),
        ),
        worldFlags = mapOf(
            "campaign.mode" to modeFlag,
            "campaign.chapter" to "0",
            "campaign.version" to "1",
        ),
    )

    private fun soloCompanionDraft(): CharacterDraft = CharacterDraft(
        name = "Mako",
        age = 24,
        origin = "West Blue",
        appearance = "hair=brown;skin=medium;outfit=deck;accessory=bandana;color=blue",
        personality = "Calmo, observador e protetor",
        dream = "Encontrar a ilha que desaparece dos mapas",
        fear = "Abandonar alguém para trás",
        profession = "Navegador",
        combatStyle = "Pistoleiro",
        background = "Sobreviveu anos conduzindo navios mercantes por rotas perigosas",
        motivation = "Descoberta",
        pirateRelation = "Pragmático",
        marineRelation = "Desconfiado",
        importantPerson = "Antiga capitã mercante",
        defect = "Demora a confiar",
        attributes = mapOf(
            Attribute.FOR to 1, Attribute.DES to 2, Attribute.CON to 2,
            Attribute.INT to 2, Attribute.PER to 2, Attribute.CAR to 0, Attribute.VON to 1,
        ),
        skills = mapOf(
            Skill.FIREARMS to 2, Skill.NAVIGATION to 2, Skill.PERCEPTION to 1,
            Skill.SURVIVAL to 1, Skill.INSIGHT to 1, Skill.ACROBATICS to 1,
        ),
    )

    private fun campaignSeed(campaignId: String): Long = campaignId.hashCode().toLong() * 0x9E3779B9L
    private fun nextCommandId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    companion object {
        val CAMPAIGN_ISLANDS = listOf("emberwake", "brineveil", "gearfall", "hollow-crown", "meridian-vault")
    }

    private fun closeSessionResources() {
        runCatching { clientConnection?.close() }
        runCatching { hostServer?.close() }
        clientConnection = null
        clientReplica = null
        hostServer = null
        advertisement = null
        hostReplica = null
        handler = null
        durableStore = null
        mode = SessionMode.NONE
        actorId = "p1"
    }

    override fun close() = closeSessionResources()
}
