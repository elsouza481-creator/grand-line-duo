package grandlineduo.game.network

import grandlineduo.core.commands.ReplaceWorldStateCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.network.GameplayCommandHandler
import grandlineduo.core.network.GameplayWireCommand
import grandlineduo.core.network.HostReplica
import grandlineduo.core.persistence.SnapshotStore
import grandlineduo.core.persistence.DurableCampaignStore
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.game.InventoryEngine
import grandlineduo.game.ShopEngine
import grandlineduo.game.EquipmentSlot
import grandlineduo.game.arc.ArcCoordinator
import grandlineduo.game.arc.ArcCombatCoordinator
import grandlineduo.game.arc.ArcBossFactory
import grandlineduo.game.combat.*
import grandlineduo.game.character.CharacterCreation
import grandlineduo.game.character.CharacterCreationResult
import grandlineduo.game.character.CharacterStateSync
import grandlineduo.game.character.ProgressionEngine
import grandlineduo.game.character.ProgressionResult
import grandlineduo.game.character.Attribute
import grandlineduo.game.character.Skill
import grandlineduo.game.director.DirectorDifficulty
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.crew.CrewEngine
import grandlineduo.game.crew.CrewRecruitmentCatalog
import grandlineduo.game.crew.CrewRole
import grandlineduo.game.quest.QuestDirectorBridge
import grandlineduo.game.quest.QuestEngine
import grandlineduo.game.quest.QuestBossCoordinator
import grandlineduo.game.scenario.StormglassCayScenario
import grandlineduo.game.powers.PowerTechniqueEngine
import grandlineduo.game.powers.PowerDiscoveryEngine
import grandlineduo.game.powers.HakiEngine
import grandlineduo.game.powers.HakiType
import grandlineduo.game.powers.HakiTrigger
import grandlineduo.game.powers.HakiAwakeningResult
import grandlineduo.game.powers.HakiMasteryResult
import grandlineduo.game.powers.DevilFruitEngine
import grandlineduo.game.powers.DevilFruitConsumeResult
import grandlineduo.game.powers.DevilFruitMasteryResult
import grandlineduo.game.ship.ShipCoordinator
import grandlineduo.game.ship.ShipEngine
import grandlineduo.game.ship.ShipUpgrade
import grandlineduo.game.ship.VoyageAction
import grandlineduo.game.ship.VoyageEngine

class StormglassGameplayCommandHandler(
    private val hostReplica: HostReplica,
    private val seed: Long,
    private val snapshotStore: SnapshotStore? = null,
    private val durableStore: DurableCampaignStore? = null,
) : GameplayCommandHandler {
    private val scenarioEngine = StormglassCayScenario()
    private val arcCoordinator = ArcCoordinator(hostReplica, snapshotStore, durableStore)
    private val arcCombatCoordinator = ArcCombatCoordinator(hostReplica, snapshotStore, durableStore)
    private val questBossCoordinator = QuestBossCoordinator(
        hostReplica = hostReplica,
        campaignSeed = seed,
        snapshotStore = snapshotStore,
        durableStore = durableStore,
    )

    @Synchronized
    override fun handle(command: GameplayWireCommand, hostTimestamp: Long): CampaignEvent {
        val fingerprint = command.fingerprint()
        hostReplica.events.firstOrNull { it.commandId == command.commandId }?.let { existing ->
            require(existing.commandFingerprint == fingerprint) { "Command ID collision" }
            persist(existing)
            return existing
        }

        require(command.actorId == "p1" || command.actorId == "p2") { "Unknown player ${command.actorId}" }
        val before = hostReplica.state
        if (command is GameplayWireCommand.CharacterCreate) {
            return applyCharacterCreate(before, command, fingerprint, hostTimestamp)
        }
        if (command is GameplayWireCommand.VoyageAction) {
            return applyVoyageAction(before, command, fingerprint, hostTimestamp)
        }
        if (command is GameplayWireCommand.ArcChoice) {
            return arcCoordinator.choose(command.commandId, command.actorId, command.choiceId, hostTimestamp)
        }
        if (command is GameplayWireCommand.InventoryAction) {
            return applyInventoryAction(before, command, fingerprint, hostTimestamp)
        }
        if (command is GameplayWireCommand.WorldAction) {
            return applyWorldAction(before, command, fingerprint, hostTimestamp)
        }
        if (command is GameplayWireCommand.PowerAction) {
            return applyPowerAction(before, command, fingerprint, hostTimestamp)
        }
        if (command is GameplayWireCommand.QuestAction) {
            if (command.actionType.equals("START_BOSS", ignoreCase = true)) {
                return questBossCoordinator.start(
                    command.commandId,
                    command.actorId,
                    command.questId,
                    hostTimestamp,
                )
            }
            return applyQuestAction(before, command, fingerprint, hostTimestamp)
        }
        if (command is GameplayWireCommand.CombatAction && before.activeCombat != null) {
            val type = try {
                CombatActionType.valueOf(command.actionType)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Unknown combat action ${command.actionType}")
            }
            require(type in BASIC_COMBAT_ACTIONS) { "Power techniques require a power action" }
            return if (before.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] != null) {
                questBossCoordinator.submitAction(
                    command.commandId,
                    command.actorId,
                    type,
                    hostTimestamp,
                )
            } else {
                arcCombatCoordinator.submitAction(
                    command.commandId,
                    command.actorId,
                    type,
                    hostTimestamp,
                )
            }
        }
        val restored = StormglassPersistenceAdapter.decode(before)

        val transition = when (command) {
            is GameplayWireCommand.ScenarioChoice -> applyScenarioChoice(restored.scenario, restored.combat, command)
            is GameplayWireCommand.CombatAction -> applyCombatAction(restored.scenario, restored.combat, command)
            is GameplayWireCommand.CharacterCreate -> error("handled above")
            is GameplayWireCommand.VoyageAction -> error("handled above")
            is GameplayWireCommand.ArcChoice -> error("handled above")
            is GameplayWireCommand.InventoryAction -> error("handled above")
            is GameplayWireCommand.WorldAction -> error("handled above")
            is GameplayWireCommand.PowerAction -> error("handled above")
            is GameplayWireCommand.QuestAction -> error("handled above")
            is GameplayWireCommand.DuelAction -> throw IllegalArgumentException("Duel lifecycle is not available yet")
        }
        val nextWorld = StormglassPersistenceAdapter.encode(before, transition.scenario, transition.combat)
        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = command.commandId,
                actorId = command.actorId,
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = transition.metadata,
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun applyCharacterCreate(
        before: grandlineduo.core.model.WorldState,
        command: GameplayWireCommand.CharacterCreate,
        fingerprint: String,
        hostTimestamp: Long,
    ): CampaignEvent {
        val currentPlayer = before.players[command.actorId]
            ?: throw IllegalArgumentException("Unknown player ${command.actorId}")
        require(currentPlayer.profile == null) { "Character already created for ${command.actorId}" }
        val creation = CharacterCreation.create(command.draft)
        val createdProfile = when (creation) {
            is CharacterCreationResult.Success -> creation.profile
            is CharacterCreationResult.Invalid -> throw IllegalArgumentException(
                "Invalid character: ${creation.errors.joinToString("; ")}"
            )
        }
        val profile = createdProfile.copy(
            haki = createdProfile.haki.copy(
                latentHaoshoku = PowerDiscoveryEngine.hasLatentHaoshoku(seed, command.actorId),
            ),
        )
        val nextPlayer = currentPlayer.copy(
            name = profile.name,
            hp = profile.maxHp,
            maxHp = profile.maxHp,
            energy = profile.maxEnergy,
            maxEnergy = profile.maxEnergy,
            profile = profile,
        )
        var nextWorld = before.copy(players = before.players + (command.actorId to nextPlayer))
        nextWorld = InventoryEngine.grantStarterKit(nextWorld, command.actorId, profile.combatStyle)
        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = command.commandId,
                actorId = command.actorId,
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = mapOf("meta.characterCreated" to command.actorId),
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun applyInventoryAction(
        before: grandlineduo.core.model.WorldState,
        command: GameplayWireCommand.InventoryAction,
        fingerprint: String,
        hostTimestamp: Long,
    ): CampaignEvent {
        require(before.activeCombat == null) { "Inventory equipment cannot change during combat" }
        require(before.activeVoyage == null) { "Inventory equipment cannot change during a voyage incident" }
        val nextWorld = when (command.actionType.uppercase()) {
            "EQUIP" -> InventoryEngine.equip(before, command.actorId, command.target)
            "UNEQUIP" -> InventoryEngine.unequip(before, command.actorId, EquipmentSlot.valueOf(command.target.uppercase()))
            "USE" -> InventoryEngine.use(before, command.actorId, command.target)
            "DISCARD" -> InventoryEngine.discard(before, command.actorId, command.target, command.amount)
            else -> throw IllegalArgumentException("Unknown inventory action ${command.actionType}")
        }
        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = command.commandId,
                actorId = command.actorId,
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = mapOf(
                    "meta.inventoryAction" to command.actionType.uppercase(),
                    "meta.inventoryTarget" to command.target,
                ),
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun applyQuestAction(
        before: grandlineduo.core.model.WorldState,
        command: GameplayWireCommand.QuestAction,
        fingerprint: String,
        hostTimestamp: Long,
    ): CampaignEvent {
        require(before.activeCombat == null && StormglassPersistenceAdapter.decode(before).combat == null) {
            "Quest management is unavailable during combat"
        }
        require(before.activeVoyage == null) { "Quest management is unavailable during a voyage incident" }
        val action = command.actionType.uppercase()
        val nextWorld = when (action) {
            "REFRESH" -> {
                require(command.questId.isBlank()) { "Quest refresh cannot target a quest" }
                QuestDirectorBridge.refresh(
                    world = before,
                    seed = seed,
                    difficulty = DirectorDifficulty.NORMAL,
                    presentFactions = (
                        before.socialState.factionStanding.keys +
                            setOf("CIVILIANS", "MARINES", "UNDERWORLD")
                    ).toSet(),
                )
            }
            "ACCEPT" -> QuestEngine.accept(before, command.questId, command.actorId)
            "PROGRESS" -> QuestEngine.progress(before, command.questId, command.amount)
            "TURN_IN" -> QuestEngine.turnIn(before, command.questId)
            "FAIL" -> QuestEngine.fail(before, command.questId, "abandoned by ${command.actorId}")
            else -> throw IllegalArgumentException("Unknown quest action ${command.actionType}")
        }
        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = command.commandId,
                actorId = command.actorId,
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = mapOf(
                    "meta.questAction" to action,
                    "meta.questId" to command.questId,
                ),
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun applyWorldAction(
        before: grandlineduo.core.model.WorldState,
        command: GameplayWireCommand.WorldAction,
        fingerprint: String,
        hostTimestamp: Long,
    ): CampaignEvent {
        require(before.activeCombat == null && StormglassPersistenceAdapter.decode(before).combat == null) {
            "World management is unavailable during combat"
        }
        require(before.activeVoyage == null) { "World management is unavailable during a voyage incident" }
        val nextWorld = when (command.actionType.uppercase()) {
            "SHOP_BUY" -> ShopEngine.buy(before, command.actorId, command.target, command.amount)
            "SHOP_SELL" -> ShopEngine.sell(before, command.actorId, command.target, command.amount)
            "SHIP_REPAIR" -> {
                require(command.amount > 0) { "Requested repair must be positive" }
                val ship = before.shipState ?: throw IllegalArgumentException("Campaign has no ship")
                val actual = minOf(command.amount, ship.maxHull - ship.hull)
                require(actual > 0) { "Ship does not need repair" }
                val cost = actual.toLong() * ShipCoordinator.REPAIR_COST_PER_HULL
                require(before.partyBerries >= cost) { "Insufficient Berries for repair" }
                before.copy(partyBerries = before.partyBerries - cost, shipState = ShipEngine.repair(ship, actual))
            }
            "SHIP_RESUPPLY" -> {
                require(command.amount > 0) { "Requested supplies must be positive" }
                val ship = before.shipState ?: throw IllegalArgumentException("Campaign has no ship")
                val actual = minOf(command.amount, ship.maxSupplies - ship.supplies)
                require(actual > 0) { "Ship supplies are already full" }
                val cost = actual.toLong() * ShipCoordinator.SUPPLY_COST_PER_UNIT
                require(before.partyBerries >= cost) { "Insufficient Berries for supplies" }
                before.copy(partyBerries = before.partyBerries - cost, shipState = ShipEngine.resupply(ship, actual))
            }
            "SHIP_UPGRADE" -> {
                val ship = before.shipState ?: throw IllegalArgumentException("Campaign has no ship")
                val upgrade = ShipUpgrade.valueOf(command.target.uppercase())
                val cost = ShipEngine.upgradeCost(ship, upgrade)
                require(before.partyBerries >= cost) { "Insufficient Berries for ship upgrade" }
                before.copy(partyBerries = before.partyBerries - cost, shipState = ShipEngine.applyUpgrade(ship, upgrade))
            }
            "CREW_RECRUIT" -> {
                val ship = before.shipState ?: throw IllegalArgumentException("Campaign has no ship")
                val member = CrewRecruitmentCatalog.requireCandidate(before.islandId, command.target)
                before.copy(crewState = CrewEngine.recruit(before.crewState, ship, member))
            }
            "CREW_ROLE" -> {
                val parts = command.target.split('|', limit = 2)
                require(parts.size == 2) { "Crew role target must contain npc id and role" }
                val member = before.crewState.members[parts[0]] ?: throw IllegalArgumentException("Unknown crew member")
                val role = CrewRole.valueOf(parts[1].uppercase())
                before.copy(crewState = before.crewState.copy(members = before.crewState.members + (member.npcId to CrewEngine.assignRole(member, role))))
            }
            "TRAIN_ATTRIBUTE" -> updateProfile(before, command.actorId) {
                ProgressionEngine.markAttributeTraining(it, Attribute.valueOf(command.target.uppercase()))
            }
            "UPGRADE_ATTRIBUTE" -> updateProfile(before, command.actorId) { profile ->
                when (val result = ProgressionEngine.increaseAttribute(profile, Attribute.valueOf(command.target.uppercase()))) {
                    is ProgressionResult.Success -> result.profile
                    is ProgressionResult.Rejected -> throw IllegalArgumentException("Attribute progression rejected: ${result.error}")
                }
            }
            "TRAIN_SKILL" -> updateProfile(before, command.actorId) {
                ProgressionEngine.markSkillTraining(it, Skill.valueOf(command.target.uppercase()))
            }
            "UPGRADE_SKILL" -> updateProfile(before, command.actorId) { profile ->
                when (val result = ProgressionEngine.increaseSkill(profile, Skill.valueOf(command.target.uppercase()))) {
                    is ProgressionResult.Success -> result.profile
                    is ProgressionResult.Rejected -> throw IllegalArgumentException("Skill progression rejected: ${result.error}")
                }
            }
            "HAKI_AWAKEN" -> updateProfile(before, command.actorId) { profile ->
                val type = HakiType.valueOf(command.target.uppercase())
                val chapter = before.worldFlags["campaign.chapter"]?.toIntOrNull() ?: 0
                val trigger = if (type == HakiType.HAOSHOKU) HakiTrigger.EXTREME_WILL else HakiTrigger.TRAINING
                val intensity = if (type == HakiType.HAOSHOKU) {
                    require(chapter >= 4) { "Haoshoku can only awaken under an extreme late-campaign trigger" }
                    5
                } else (2 + chapter / 2).coerceAtMost(5)
                when (val result = HakiEngine.attemptAwakening(profile, profile.haki, type, trigger, intensity)) {
                    is HakiAwakeningResult.Awakened -> profile.copy(haki = result.state)
                    is HakiAwakeningResult.Rejected -> throw IllegalArgumentException("Haki awakening rejected: ${result.reason}")
                }
            }
            "HAKI_TRAIN" -> updateProfile(before, command.actorId) { profile ->
                val type = HakiType.valueOf(command.target.uppercase())
                when (val result = HakiEngine.trainMastery(profile.haki, type)) {
                    is HakiMasteryResult.Advanced -> profile.copy(haki = result.state)
                    is HakiMasteryResult.Rejected -> throw IllegalArgumentException("Haki mastery rejected: ${result.reason}")
                }
            }
            "FRUIT_EAT" -> {
                val discoveredId = before.worldFlags["fruit.discovery.id"]
                    ?: throw IllegalArgumentException("No Devil Fruit is available")
                require(command.target.isBlank() || command.target == discoveredId) { "That Devil Fruit is not available" }
                val definition = PowerDiscoveryEngine.definition(discoveredId)
                val updated = updateProfile(before, command.actorId) { profile ->
                    when (val result = DevilFruitEngine.consume(profile.devilFruit, definition, identified = false)) {
                        is DevilFruitConsumeResult.Consumed -> profile.copy(devilFruit = result.state)
                        is DevilFruitConsumeResult.Rejected -> throw IllegalArgumentException("Devil Fruit rejected: ${result.reason}")
                    }
                }
                updated.copy(worldFlags = updated.worldFlags - "fruit.discovery.id")
            }
            "FRUIT_IDENTIFY" -> {
                val cost = 1_000L
                require(before.partyBerries >= cost) { "Insufficient Berries to identify the Devil Fruit" }
                val updated = updateProfile(before, command.actorId) { profile ->
                    val fruit = profile.devilFruit ?: throw IllegalArgumentException("Character has no Devil Fruit")
                    profile.copy(devilFruit = DevilFruitEngine.revealIdentity(fruit, PowerDiscoveryEngine.definition(fruit.fruitId)))
                }
                updated.copy(partyBerries = updated.partyBerries - cost)
            }
            "FRUIT_TRAIN" -> updateProfile(before, command.actorId) { profile ->
                val fruit = profile.devilFruit ?: throw IllegalArgumentException("Character has no Devil Fruit")
                when (val result = DevilFruitEngine.trainMastery(fruit)) {
                    is DevilFruitMasteryResult.Advanced -> profile.copy(devilFruit = result.state)
                    is DevilFruitMasteryResult.Rejected -> throw IllegalArgumentException("Devil Fruit mastery rejected: ${result.reason}")
                }
            }
            else -> throw IllegalArgumentException("Unknown world action ${command.actionType}")
        }
        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = command.commandId,
                actorId = command.actorId,
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = mapOf("meta.worldAction" to command.actionType.uppercase(), "meta.worldTarget" to command.target),
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun updateProfile(
        world: grandlineduo.core.model.WorldState,
        playerId: String,
        transform: (grandlineduo.game.character.CharacterProfile) -> grandlineduo.game.character.CharacterProfile,
    ): grandlineduo.core.model.WorldState {
        val player = world.players[playerId] ?: throw IllegalArgumentException("Unknown player $playerId")
        val profile = player.profile ?: throw IllegalArgumentException("Character not created for $playerId")
        val updatedPlayer = CharacterStateSync.applyProfile(player, transform(profile))
        return world.copy(players = world.players + (playerId to updatedPlayer))
    }

    private fun applyPowerAction(
        before: grandlineduo.core.model.WorldState,
        command: GameplayWireCommand.PowerAction,
        fingerprint: String,
        hostTimestamp: Long,
    ): CampaignEvent {
        val prepared = PowerTechniqueEngine.prepare(before, command.actorId, command.techniqueId)
        val poweredWorld = prepared.world
        val metadata = mutableMapOf(
            "meta.powerTechnique" to prepared.technique.id,
            "meta.powerEnergyCost" to prepared.technique.energyCost.toString(),
            "meta.powerBonus" to prepared.bonusDamage.toString(),
        )
        if (
            poweredWorld.activeCombat != null &&
            poweredWorld.worldFlags[QuestBossCoordinator.ACTIVE_QUEST_FLAG] != null
        ) {
            return questBossCoordinator.submitPreparedAction(
                commandId = command.commandId,
                playerId = command.actorId,
                actionType = prepared.combatAction,
                preparedWorld = poweredWorld,
                sourceFingerprint = fingerprint,
                metadata = metadata,
                hostTimestamp = hostTimestamp,
            )
        }

        val nextWorld = if (poweredWorld.activeCombat != null) {
            val arc = poweredWorld.activeArc ?: throw IllegalArgumentException("Active boss combat has no arc")
            val current = poweredWorld.activeCombat
            val engine = CombatEngine(ArcBossFactory.combatSeed(arc), CombatModifierResolver.forWorld(poweredWorld))
            val locked = try {
                engine.lockAction(current, CombatAction(command.actorId, prepared.combatAction))
            } catch (e: CombatRuleException) {
                throw IllegalArgumentException(e.message ?: "Invalid power action")
            }
            val resolved = engine.resolveIfReady(locked)
            if (resolved == null) {
                metadata["meta.roundResolved"] = "false"
                poweredWorld.copy(activeCombat = locked)
            } else {
                metadata["meta.roundResolved"] = "true"
                metadata["meta.coopCombo"] = resolved.coopCombo.toString()
                metadata["meta.combatStatus"] = resolved.state.status.name
                metadata["meta.enemyDamage"] = resolved.enemyDamage.toString()
                metadata["meta.combatLog"] = resolved.log.joinToString("\n")
                val players = poweredWorld.players.mapValues { (id, player) ->
                    resolved.state.players[id]?.let { fighter -> player.copy(hp = fighter.hp, maxHp = fighter.maxHp) } ?: player
                }
                when (resolved.state.status) {
                    CombatStatus.VICTORY -> poweredWorld.copy(
                        players = players,
                        activeCombat = null,
                        worldFlags = poweredWorld.worldFlags + ("ARC_BOSS_DEFEATED:${arc.arcId}" to "true"),
                    )
                    CombatStatus.DEFEAT -> poweredWorld.copy(
                        players = players,
                        activeCombat = resolved.state,
                        worldFlags = poweredWorld.worldFlags + ("ARC_PARTY_DEFEATED:${arc.arcId}" to "true"),
                    )
                    CombatStatus.ACTIVE -> poweredWorld.copy(players = players, activeCombat = resolved.state)
                }
            }
        } else {
            val restored = StormglassPersistenceAdapter.decode(poweredWorld)
            val current = restored.combat ?: throw IllegalArgumentException("Combat is not active")
            val engine = CombatEngine(seed, CombatModifierResolver.forWorld(poweredWorld))
            val locked = try {
                engine.lockAction(current, CombatAction(command.actorId, prepared.combatAction))
            } catch (e: CombatRuleException) {
                throw IllegalArgumentException(e.message ?: "Invalid power action")
            }
            val resolved = engine.resolveIfReady(locked)
            if (resolved == null) {
                metadata["meta.roundResolved"] = "false"
                StormglassPersistenceAdapter.encode(poweredWorld, restored.scenario, locked)
            } else {
                metadata["meta.roundResolved"] = "true"
                metadata["meta.coopCombo"] = resolved.coopCombo.toString()
                metadata["meta.combatStatus"] = resolved.state.status.name
                metadata["meta.enemyDamage"] = resolved.enemyDamage.toString()
                metadata["meta.combatLog"] = resolved.log.joinToString("\n")
                val syncedPlayers = poweredWorld.players.mapValues { (id, player) ->
                    resolved.state.players[id]?.let { fighter -> player.copy(hp = fighter.hp, maxHp = fighter.maxHp) } ?: player
                }
                val won = resolved.state.status == CombatStatus.VICTORY
                val scenario = if (won) scenarioEngine.markMinibossDefeated(restored.scenario) else restored.scenario
                val combat = if (won) null else resolved.state
                StormglassPersistenceAdapter.encode(poweredWorld.copy(players = syncedPlayers), scenario, combat)
            }
        }

        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = command.commandId,
                actorId = command.actorId,
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = metadata,
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun applyVoyageAction(
        before: grandlineduo.core.model.WorldState,
        command: GameplayWireCommand.VoyageAction,
        fingerprint: String,
        hostTimestamp: Long,
    ): CampaignEvent {
        val ship = before.shipState ?: throw IllegalArgumentException("Campaign has no ship")
        val active = before.activeVoyage ?: throw IllegalArgumentException("No voyage incident is active")
        val action = try {
            VoyageAction.valueOf(command.actionType)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown voyage action ${command.actionType}")
        }
        val locked = VoyageEngine.lockAction(active, command.actorId, action)
        val resolution = VoyageEngine.resolveIfReady(ship, locked, before.crewState)
        val nextWorld = if (resolution == null) {
            before.copy(activeVoyage = locked)
        } else {
            before.copy(shipState = resolution.shipAfter, activeVoyage = null)
        }
        val metadata = if (resolution == null) {
            mapOf(
                "meta.voyage" to "ACTION_LOCKED",
                "meta.voyageResolved" to "false",
                "meta.voyagePlayer" to command.actorId,
                "meta.voyageAction" to action.name,
            )
        } else {
            mapOf(
                "meta.voyage" to "RESOLVED",
                "meta.voyageResolved" to "true",
                "meta.voyageSuccess" to resolution.success.toString(),
                "meta.voyageSynergy" to (resolution.coopSynergy ?: ""),
                "meta.voyageHullDamage" to resolution.hullDamage.toString(),
                "meta.voyageSupplyLoss" to resolution.supplyLoss.toString(),
            )
        }
        val result = hostReplica.submit(
            ReplaceWorldStateCommand(
                commandId = command.commandId,
                actorId = command.actorId,
                nextState = nextWorld,
                sourceFingerprint = fingerprint,
                metadata = metadata,
            ),
            hostTimestamp,
        )
        persist(result.event)
        return result.event
    }

    private fun persist(event: CampaignEvent) {
        if (durableStore != null) durableStore.commit(event, hostReplica.state)
        else snapshotStore?.save(hostReplica.state)
    }

    private data class Transition(
        val scenario: grandlineduo.game.scenario.ScenarioState,
        val combat: CombatState?,
        val metadata: Map<String, String>,
    )

    private fun applyScenarioChoice(
        scenario: grandlineduo.game.scenario.ScenarioState,
        combat: CombatState?,
        command: GameplayWireCommand.ScenarioChoice,
    ): Transition {
        require(combat == null || combat.status != CombatStatus.ACTIVE) { "Combat is active" }
        val outcome = scenarioEngine.choose(scenario, command.actorId, command.choiceId)
        var nextCombat = combat
        if (outcome.state.stage == ScenarioStage.MINIBOSS && nextCombat == null) {
            nextCombat = initialVeyronCombat(outcome.state.sharedFlags)
        }
        val metadata = mutableMapOf<String, String>()
        outcome.beats.forEachIndexed { index, beat ->
            metadata["meta.beat.$index.visible"] = beat.visibleTo.sorted().joinToString(",")
            metadata["meta.beat.$index.text"] = beat.text
        }
        return Transition(outcome.state, nextCombat, metadata)
    }

    private fun applyCombatAction(
        scenario: grandlineduo.game.scenario.ScenarioState,
        combat: CombatState?,
        command: GameplayWireCommand.CombatAction,
    ): Transition {
        val current = combat ?: throw IllegalArgumentException("Combat is not active")
        val type = try {
            CombatActionType.valueOf(command.actionType)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown combat action ${command.actionType}")
        }
        require(type in BASIC_COMBAT_ACTIONS) { "Power techniques require a power action" }
        val combatEngine = CombatEngine(seed, CombatModifierResolver.forWorld(hostReplica.state))
        val locked = combatEngine.lockAction(current, CombatAction(command.actorId, type))
        val resolved = combatEngine.resolveIfReady(locked)
        if (resolved == null) {
            return Transition(
                scenario,
                locked,
                mapOf("meta.roundResolved" to "false", "meta.coopCombo" to "false"),
            )
        }

        val won = resolved.state.status == CombatStatus.VICTORY
        val nextScenario = if (won) scenarioEngine.markMinibossDefeated(scenario) else scenario
        val nextCombat = if (won) null else resolved.state
        return Transition(
            nextScenario,
            nextCombat,
            mapOf(
                "meta.roundResolved" to "true",
                "meta.coopCombo" to resolved.coopCombo.toString(),
                "meta.combatLog" to resolved.log.joinToString("\n"),
                "meta.combatStatus" to resolved.state.status.name,
            ),
        )
    }

    private fun initialVeyronCombat(sharedFlags: Set<String>): CombatState {
        val world = hostReplica.state
        return CombatState(
            round = 1,
            players = world.players.mapValues { (id, player) ->
                Combatant(id, player.name, player.hp, player.maxHp)
            },
            enemy = EnemyCombatant(
                id = "veyron",
                name = "Capitão Veyron",
                hp = if ("ambush_prepared" in sharedFlags) 105 else 120,
                maxHp = 120,
                attackPower = 18,
            ),
            telegraph = EnemyTelegraph(EnemyAttackType.HEAVY_STRIKE, "p1"),
        )
    }

    companion object {
        private val BASIC_COMBAT_ACTIONS = setOf(
            CombatActionType.ATTACK,
            CombatActionType.DEFEND,
            CombatActionType.DODGE,
            CombatActionType.SETUP,
            CombatActionType.FINISHER,
        )
    }

}
