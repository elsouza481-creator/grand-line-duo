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
import grandlineduo.game.world.ExplorationEngine
import grandlineduo.game.world.ExplorationInteraction
import grandlineduo.game.world.GrandLineWorldAtlas
import java.io.Closeable
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

enum class SessionMode { NONE, SOLO, HOST_COOP, CLIENT_COOP }

data class SessionHudState(
    val mode: SessionMode,
    val localActorId: String,
    val networkConnectedCount: Int?,
    val maxNetworkPlayers: Int,
    val networkConnectedPlayerIds: Set<String>,
    val createdPlayerIds: Set<String>,
) {
    val badge: String
        get() = when (mode) {
            SessionMode.HOST_COOP -> {
                val slots = networkConnectedPlayerIds.sorted().joinToString(", ") { it.uppercase() }
                "LAN ${networkConnectedCount ?: 1}/$maxNetworkPlayers • slot ${localActorId.uppercase()} • conectados $slots"
            }
            SessionMode.CLIENT_COOP ->
                "LAN • slot ${localActorId.uppercase()} • tripulação ${createdPlayerIds.size}/$maxNetworkPlayers criada"
            SessionMode.SOLO -> "SOLO • ${localActorId.uppercase()} • tripulação ${createdPlayerIds.size}/2"
            SessionMode.NONE -> "SEM SESSÃO"
        }
}

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
    fun sessionHudState(): SessionHudState {
        val createdPlayerIds = if (mode == SessionMode.NONE) {
            emptySet()
        } else {
            runCatching { worldState() }.getOrNull()
                ?.players
                ?.values
                ?.asSequence()
                ?.filter { it.profile != null }
                ?.map { it.playerId }
                ?.filter { it in HUMAN_PLAYER_IDS }
                ?.toSortedSet()
                ?: emptySet()
        }
        return when (mode) {
            SessionMode.HOST_COOP -> {
                val connected = linkedSetOf("p1").apply {
                    addAll(hostServer?.activeClientIds.orEmpty())
                }
                SessionHudState(
                    mode = mode,
                    localActorId = actorId,
                    networkConnectedCount = connected.size,
                    maxNetworkPlayers = 4,
                    networkConnectedPlayerIds = connected,
                    createdPlayerIds = createdPlayerIds,
                )
            }
            SessionMode.CLIENT_COOP -> SessionHudState(
                mode = mode,
                localActorId = actorId,
                networkConnectedCount = null,
                maxNetworkPlayers = 4,
                networkConnectedPlayerIds = emptySet(),
                createdPlayerIds = createdPlayerIds,
            )
            SessionMode.SOLO -> SessionHudState(
                mode = mode,
                localActorId = actorId,
                networkConnectedCount = null,
                maxNetworkPlayers = 2,
                networkConnectedPlayerIds = emptySet(),
                createdPlayerIds = createdPlayerIds,
            )
            SessionMode.NONE -> SessionHudState(
                mode = mode,
                localActorId = actorId,
                networkConnectedCount = null,
                maxNetworkPlayers = 0,
                networkConnectedPlayerIds = emptySet(),
                createdPlayerIds = emptySet(),
            )
        }
    }

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
        val liveAdvertisement = ad.copy(
            currentPlayers = 1 + (hostServer?.activeClientCount ?: 0),
            maxPlayers = 4,
        )
        LanDiscoveryAdvertiser(targetAddress, discoveryPort).use { it.send(liveAdvertisement) }
    }

    @Synchronized
    fun discoverAndJoin(
        timeoutMillis: Int = 5_000,
        bindAddress: String = "0.0.0.0",
        discoveryPort: Int = 37778,
    ): WorldState {
        closeSessionResources()
        mode = SessionMode.CLIENT_COOP
        val discovered = LanDiscoveryListener(bindAddress, discoveryPort).use { listener ->
            listener.start()
            listener.receive(timeoutMillis)
        } ?: throw IllegalStateException("Nenhuma aventura compatível foi encontrada")
        val ad = discovered.advertisement
        val replica = ClientReplica(WorldState(campaignId = ad.campaignId))
        val connection = LanClientConnection(
            host = discovered.sourceAddress.hostAddress,
            port = ad.tcpPort,
            peerId = LanClientConnection.AUTO_SLOT,
            replica = replica,
        )
        connection.connect()
        actorId = connection.assignedPeerId
            ?: throw IllegalStateException("O host não atribuiu um slot de jogador")
        clientReplica = replica
        clientConnection = connection
        return replica.state
    }

    @Synchronized
    fun refresh(): WorldState {
        when (mode) {
            SessionMode.CLIENT_COOP -> clientConnection?.refresh()
            SessionMode.SOLO -> {
                autoPlayCompanion()
                postProcessHostState()
            }
            SessionMode.HOST_COOP -> postProcessHostState()
            SessionMode.NONE -> Unit
        }
        return worldState()
    }

    @Synchronized
    fun reconnect(): WorldState {
        require(mode == SessionMode.CLIENT_COOP) { "Only a LAN client can reconnect" }
        val connection = clientConnection ?: throw IllegalStateException("No client connection")
        connection.connect()
        actorId = connection.assignedPeerId
            ?: throw IllegalStateException("The host did not preserve the assigned player slot")
        return clientReplica?.state ?: throw IllegalStateException("No client campaign")
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
    fun advanceCampaign(targetIslandId: String? = null): WorldState {
        require(mode == SessionMode.SOLO || mode == SessionMode.HOST_COOP) { "Only P1 can set sail" }
        postProcessHostState()
        if (mode == SessionMode.SOLO) recoverSoloCompanionBeforeVoyage()
        val world = worldState()
        require(ExplorationEngine.interactionAt(world, "p1") == ExplorationInteraction.DOCK) {
            "P1 must be at the physical dock to set sail"
        }
        require(world.activeCombat == null && StormglassPersistenceAdapter.decode(world).combat == null) { "Cannot sail during combat" }
        require(world.activeVoyage == null) { "A voyage incident is already active" }
        val scenarioComplete = StormglassPersistenceAdapter.decode(world).scenario.stage == grandlineduo.game.scenario.ScenarioStage.COMPLETE
        val arcComplete = world.activeArc?.phase == ArcPhase.COMPLETE
        require(scenarioComplete || arcComplete) { "Current chapter is not complete" }

        val voyageIndex = world.worldFlags["world.voyages"]?.toIntOrNull()
            ?: world.worldFlags["campaign.chapter"]?.toIntOrNull()
            ?: 0
        val routes = GrandLineWorldAtlas.availableDestinations(world.campaignId, world.islandId, voyageIndex)
        val target = if (targetIslandId.isNullOrBlank()) {
            routes.first()
        } else {
            routes.firstOrNull { it.id == targetIslandId }
                ?: throw IllegalArgumentException("Destination $targetIslandId is not available from ${world.islandId}")
        }
        val incidentType = VoyageIncidentType.entries[voyageIndex % VoyageIncidentType.entries.size]
        val voyageParticipants = if (mode == SessionMode.SOLO) {
            setOf("p1", "p2")
        } else {
            world.players.values
                .filter { it.profile != null }
                .map { it.playerId }
                .filter { it in HUMAN_PLAYER_IDS }
                .toSet()
        }
        require(voyageParticipants.size >= 2) { "At least two created players are required for a co-op voyage" }
        val encounter = VoyageEncounter(
            incident = VoyageIncident(
                type = incidentType,
                severity = ((target.danger + 2) / 3).coerceIn(1, 4),
                seed = campaignSeed(world.campaignId) xor (voyageIndex.toLong() * 7919L) xor target.id.hashCode().toLong(),
            ),
            participants = voyageParticipants,
        )
        val flags = world.worldFlags + mapOf(
            "campaign.pendingIsland" to target.id,
            "campaign.pendingIslandName" to target.name,
            "campaign.pendingDanger" to target.danger.toString(),
            "campaign.traveling" to "true",
        )
        replaceHostWorld(
            next = world.copy(activeVoyage = encounter, worldFlags = flags),
            prefix = "campaign-sail",
            fingerprint = "campaign-sail|$voyageIndex|${target.id}",
            metadata = mapOf(
                "meta.campaignSail" to target.id,
                "meta.campaignSailName" to target.name,
                "meta.campaignDanger" to target.danger.toString(),
            ),
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
        if (activeArcCombat != null) {
            if (activeArcCombat.status != grandlineduo.game.combat.CombatStatus.ACTIVE) return
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

        val restored = StormglassPersistenceAdapter.decode(host.state)
        val combat = restored.combat
        if (combat != null) {
            if (combat.status != grandlineduo.game.combat.CombatStatus.ACTIVE) return
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

    /** Solo P2 manages its own healing supplies before a new sea leg. */
    private fun recoverSoloCompanionBeforeVoyage() {
        val host = hostReplica ?: return
        var next = host.state
        val before = next.players["p2"] ?: return
        if (before.hp >= before.maxHp) return

        var used = 0
        while ((next.players["p2"]?.hp ?: 0) < (next.players["p2"]?.maxHp ?: 0)) {
            val inventory = grandlineduo.game.InventoryEngine.read(next, "p2")
            val itemId = listOf("bandage", "ration").firstOrNull { (inventory.items[it] ?: 0) > 0 } ?: break
            next = grandlineduo.game.InventoryEngine.use(next, "p2", itemId)
            used++
        }
        if (used == 0) return

        replaceHostWorld(
            next = next,
            prefix = "ai-recover",
            fingerprint = "ai-recover|${host.state.lastEventId}|$used",
            metadata = mapOf(
                "meta.aiRecovery" to "p2",
                "meta.aiRecoveryItems" to used.toString(),
                "meta.aiRecoveryHp" to next.players.getValue("p2").hp.toString(),
            ),
        )
    }

    private fun postProcessHostState() {
        if (mode != SessionMode.SOLO && mode != SessionMode.HOST_COOP) return
        val host = hostReplica ?: return
        var world = host.state

        val scenario = StormglassPersistenceAdapter.decode(world).scenario
        if (scenario.stage == grandlineduo.game.scenario.ScenarioStage.COMPLETE && world.worldFlags["reward.stormglass"] != "true") {
            var rewarded = grandlineduo.game.InventoryEngine.grant(world, "p1", "stormglass_log_pose", 1)
            rewarded = grandlineduo.game.InventoryEngine.grant(rewarded, "p1", "reinforced_coat", 1)
            world.players.values
                .asSequence()
                .filter { it.profile != null && it.playerId != "p1" && it.playerId in HUMAN_PLAYER_IDS }
                .map { it.playerId }
                .sorted()
                .forEach { playerId ->
                    rewarded = grandlineduo.game.InventoryEngine.grant(rewarded, playerId, "bandage", 2)
                }
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
                world.players.values
                    .asSequence()
                    .filter { it.profile != null && it.playerId != "p1" && it.playerId in HUMAN_PLAYER_IDS }
                    .map { it.playerId }
                    .sorted()
                    .forEach { playerId ->
                        rewarded = grandlineduo.game.InventoryEngine.grant(rewarded, playerId, "ration", 2)
                    }
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
            val voyageIndex = world.worldFlags["world.voyages"]?.toIntOrNull() ?: chapter
            val island = GrandLineWorldAtlas.describe(world.campaignId, pending)
            val flags = world.worldFlags.toMutableMap().also {
                it.remove("campaign.pendingIsland")
                it.remove("campaign.pendingIslandName")
                it.remove("campaign.pendingDanger")
                it.remove("campaign.traveling")
                it["campaign.chapter"] = (chapter + 1).toString()
                it["world.voyages"] = (voyageIndex + 1).toString()
                it["world.currentIslandName"] = island.name
                it["world.currentIslandDanger"] = island.danger.toString()
            }
            replaceHostWorld(
                world.copy(islandId = pending, activeArc = null, worldFlags = flags),
                "campaign-arrive",
                "campaign-arrive|$pending|${voyageIndex + 1}",
                mapOf(
                    "meta.campaignArrive" to pending,
                    "meta.campaignArriveName" to island.name,
                    "meta.campaignDanger" to island.danger.toString(),
                ),
            )
            val arrived = host.state
            val participants = arrived.players.values
                .asSequence()
                .filter { it.profile != null && it.playerId in HUMAN_PLAYER_IDS }
                .map { it.playerId }
                .toSet()
            val context = ArcStartContext(
                seed = campaignSeed(arrived.campaignId) xor (voyageIndex.toLong() * 104729L) xor pending.hashCode().toLong(),
                islandId = pending,
                presentFactions = island.factions,
                worldFlags = island.flags,
                totalBounty = arrived.players.values.sumOf { it.bounty },
                participantIds = participants,
            )
            ArcCoordinator(host, durableStore = durableStore).startArc(nextCommandId("arc-start"), context, System.currentTimeMillis())
        }
    }

    private fun awardPartyEvolutionPoints(world: WorldState, amount: Int): WorldState =
        world.copy(players = world.players.mapValues { (_, player) ->
            val profile = player.profile ?: return@mapValues player
            player.copy(profile = ProgressionEngine.awardEvolutionPoints(profile, amount))
        })

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
            "campaign.version" to "2",
            "world.voyages" to "0",
            "world.currentIslandName" to "Stormglass Cay",
            "world.currentIslandDanger" to "2",
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

    companion object {
        private val HUMAN_PLAYER_IDS = setOf("p1", "p2", "p3", "p4")
    }
}
