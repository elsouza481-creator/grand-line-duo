package grandlineduo.core.persistence

import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.game.character.Attribute
import grandlineduo.game.arc.ArcArchetype
import grandlineduo.game.arc.ArcPhase
import grandlineduo.game.arc.ArcState
import grandlineduo.game.character.CharacterProfile
import grandlineduo.game.character.ClassMasteryState
import grandlineduo.game.character.ClassPath
import grandlineduo.game.character.Skill
import grandlineduo.game.crew.CrewMemberState
import grandlineduo.game.crew.CrewRole
import grandlineduo.game.crew.CrewState
import grandlineduo.game.crew.CrewStatus
import grandlineduo.game.combat.EnemyTelegraph
import grandlineduo.game.combat.EnemyCombatant
import grandlineduo.game.combat.EnemyAttackType
import grandlineduo.game.combat.Combatant
import grandlineduo.game.combat.CombatStatus
import grandlineduo.game.combat.CombatState
import grandlineduo.game.combat.CombatActionType
import grandlineduo.game.combat.CombatAction
import grandlineduo.game.powers.DevilFruitCategory
import grandlineduo.game.powers.DevilFruitState
import grandlineduo.game.powers.HakiDiscipline
import grandlineduo.game.powers.HakiState
import grandlineduo.game.powers.HakiType
import grandlineduo.game.social.NpcBond
import grandlineduo.game.social.NpcRelationship
import grandlineduo.game.social.NpcStatus
import grandlineduo.game.social.SocialState
import grandlineduo.game.ship.ShipCompartment
import grandlineduo.game.ship.ShipState
import grandlineduo.game.ship.ShipUpgrade
import grandlineduo.game.ship.VoyageAction
import grandlineduo.game.ship.VoyageEncounter
import grandlineduo.game.ship.VoyageIncident
import grandlineduo.game.ship.VoyageIncidentType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object WorldStateCodec {
    private const val CURRENT_VERSION = 11

    fun encode(state: WorldState): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(CURRENT_VERSION)
            data.writeUTF(state.campaignId)
            data.writeLong(state.lastEventId)
            data.writeUTF(state.islandId)
            data.writeLong(state.partyBerries)
            data.writeInt(state.governmentThreatPoints)
            writeSocialState(data, state.socialState)
            data.writeBoolean(state.shipState != null)
            state.shipState?.let { writeShipState(data, it) }
            data.writeBoolean(state.activeVoyage != null)
            state.activeVoyage?.let { writeVoyage(data, it) }
            writeCrewState(data, state.crewState)
            data.writeBoolean(state.activeArc != null)
            state.activeArc?.let { writeArcState(data, it) }
            data.writeBoolean(state.activeCombat != null)
            state.activeCombat?.let { writeCombatState(data, it) }

            val players = state.players.toSortedMap()
            data.writeInt(players.size)
            for ((key, player) in players) {
                data.writeUTF(key)
                data.writeUTF(player.playerId)
                data.writeUTF(player.name)
                data.writeInt(player.hp)
                data.writeInt(player.maxHp)
                data.writeLong(player.bounty)
                data.writeInt(player.energy)
                data.writeInt(player.maxEnergy)
                data.writeBoolean(player.profile != null)
                player.profile?.let { writeProfile(data, it) }
            }

            val flags = state.worldFlags.toSortedMap()
            data.writeInt(flags.size)
            for ((key, value) in flags) {
                data.writeUTF(key)
                data.writeUTF(value)
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): WorldState {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val version = data.readInt()
            require(version in 1..CURRENT_VERSION) { "Unsupported world state version: $version" }
            val campaignId = data.readUTF()
            val lastEventId = data.readLong()
            val islandId = data.readUTF()
            val partyBerries = data.readLong()
            val governmentThreatPoints = if (version >= 4) data.readInt().also {
                require(it >= 0) { "Invalid government threat points" }
            } else 0
            val socialState = if (version >= 5) readSocialState(data) else SocialState()
            val shipState = if (version >= 6 && data.readBoolean()) readShipState(data) else null
            val activeVoyage = if (version >= 6 && data.readBoolean()) readVoyage(data, version) else null
            val crewState = if (version >= 7) readCrewState(data) else CrewState()
            val activeArc = if (version >= 8 && data.readBoolean()) readArcState(data) else null
            val activeCombat = if (version >= 9 && data.readBoolean()) readCombatState(data) else null

            val playerCount = data.readInt()
            require(playerCount in 0..4) { "Invalid player count" }
            val players = linkedMapOf<String, PlayerState>()
            repeat(playerCount) {
                val key = data.readUTF()
                val playerId = data.readUTF()
                val name = data.readUTF()
                val hp = data.readInt()
                val maxHp = data.readInt()
                val bounty = data.readLong()
                val energy = data.readInt()
                val maxEnergy = data.readInt()
                val profile = if (version >= 2 && data.readBoolean()) readProfile(data, version) else null
                players[key] = PlayerState(
                    playerId = playerId,
                    name = name,
                    hp = hp,
                    maxHp = maxHp,
                    bounty = bounty,
                    energy = energy,
                    maxEnergy = maxEnergy,
                    profile = profile,
                )
            }

            val flagCount = data.readInt()
            require(flagCount in 0..100_000) { "Invalid flag count" }
            val flags = linkedMapOf<String, String>()
            repeat(flagCount) { flags[data.readUTF()] = data.readUTF() }
            require(data.available() == 0) { "Trailing snapshot bytes" }

            return WorldState(
                campaignId = campaignId,
                lastEventId = lastEventId,
                islandId = islandId,
                partyBerries = partyBerries,
                governmentThreatPoints = governmentThreatPoints,
                socialState = socialState,
                shipState = shipState,
                activeVoyage = activeVoyage,
                crewState = crewState,
                activeArc = activeArc,
                activeCombat = activeCombat,
                players = players,
                worldFlags = flags,
            )
        }
    }

    private fun writeSocialState(data: DataOutputStream, socialState: SocialState) {
        val factions = socialState.factionStanding.toSortedMap()
        data.writeInt(factions.size)
        factions.forEach { (factionId, standing) ->
            data.writeUTF(factionId)
            data.writeInt(standing)
        }

        val npcs = socialState.npcRelationships.toSortedMap()
        data.writeInt(npcs.size)
        npcs.forEach { (npcId, relationship) ->
            data.writeUTF(npcId)
            data.writeInt(relationship.affinity)
            data.writeUTF(relationship.bond.name)
            data.writeUTF(relationship.status.name)
        }
    }

    private fun readSocialState(data: DataInputStream): SocialState {
        val factionCount = data.readInt()
        require(factionCount in 0..10_000) { "Invalid faction standing count" }
        val factions = linkedMapOf<String, Int>()
        repeat(factionCount) {
            val factionId = data.readUTF()
            require(factionId !in factions) { "Duplicate faction $factionId" }
            val standing = data.readInt()
            require(standing in -100..100) { "Invalid faction standing" }
            factions[factionId] = standing
        }

        val npcCount = data.readInt()
        require(npcCount in 0..100_000) { "Invalid NPC relationship count" }
        val npcs = linkedMapOf<String, NpcRelationship>()
        repeat(npcCount) {
            val npcId = data.readUTF()
            require(npcId !in npcs) { "Duplicate NPC relationship $npcId" }
            npcs[npcId] = NpcRelationship(
                affinity = data.readInt(),
                bond = NpcBond.valueOf(data.readUTF()),
                status = NpcStatus.valueOf(data.readUTF()),
            )
        }
        return SocialState(factionStanding = factions, npcRelationships = npcs)
    }

    private fun writeShipState(data: DataOutputStream, ship: ShipState) {
        data.writeUTF(ship.shipId)
        data.writeUTF(ship.name)
        data.writeInt(ship.hull)
        data.writeInt(ship.maxHull)
        data.writeInt(ship.speed)
        data.writeInt(ship.maneuverability)
        data.writeInt(ship.artillery)
        data.writeInt(ship.capacity)
        data.writeInt(ship.supplies)
        data.writeInt(ship.maxSupplies)

        val compartments = ship.compartments.sortedBy { it.ordinal }
        data.writeInt(compartments.size)
        compartments.forEach { data.writeUTF(it.name) }

        val upgrades = ship.upgrades.entries.sortedBy { it.key.ordinal }
        data.writeInt(upgrades.size)
        upgrades.forEach { (upgrade, level) ->
            data.writeUTF(upgrade.name)
            data.writeInt(level)
        }
    }

    private fun readShipState(data: DataInputStream): ShipState {
        val shipId = data.readUTF()
        val name = data.readUTF()
        val hull = data.readInt()
        val maxHull = data.readInt()
        val speed = data.readInt()
        val maneuverability = data.readInt()
        val artillery = data.readInt()
        val capacity = data.readInt()
        val supplies = data.readInt()
        val maxSupplies = data.readInt()

        val compartmentCount = data.readInt()
        require(compartmentCount in 0..ShipCompartment.entries.size) { "Invalid ship compartment count" }
        val compartments = linkedSetOf<ShipCompartment>()
        repeat(compartmentCount) {
            val compartment = ShipCompartment.valueOf(data.readUTF())
            require(compartment !in compartments) { "Duplicate ship compartment $compartment" }
            compartments += compartment
        }

        val upgradeCount = data.readInt()
        require(upgradeCount in 0..ShipUpgrade.entries.size) { "Invalid ship upgrade count" }
        val upgrades = linkedMapOf<ShipUpgrade, Int>()
        repeat(upgradeCount) {
            val upgrade = ShipUpgrade.valueOf(data.readUTF())
            require(upgrade !in upgrades) { "Duplicate ship upgrade $upgrade" }
            val level = data.readInt()
            require(level in 0..5) { "Invalid ship upgrade level" }
            upgrades[upgrade] = level
        }

        return ShipState(
            shipId = shipId,
            name = name,
            hull = hull,
            maxHull = maxHull,
            speed = speed,
            maneuverability = maneuverability,
            artillery = artillery,
            capacity = capacity,
            supplies = supplies,
            maxSupplies = maxSupplies,
            compartments = compartments,
            upgrades = upgrades,
        )
    }

    private fun writeCrewState(data: DataOutputStream, crew: CrewState) {
        val members = crew.members.toSortedMap()
        data.writeInt(members.size)
        members.forEach { (npcId, member) ->
            data.writeUTF(npcId)
            data.writeUTF(member.name)
            data.writeUTF(member.role.name)
            data.writeInt(member.competence)
            data.writeInt(member.loyalty)
            data.writeInt(member.injurySeverity)
            data.writeUTF(member.status.name)
            val affinity = member.playerAffinity.toSortedMap()
            data.writeInt(affinity.size)
            affinity.forEach { (playerId, value) ->
                data.writeUTF(playerId)
                data.writeInt(value)
            }
        }
    }

    private fun readCrewState(data: DataInputStream): CrewState {
        val count = data.readInt()
        require(count in 0..1_000) { "Invalid crew count" }
        val members = linkedMapOf<String, CrewMemberState>()
        repeat(count) {
            val npcId = data.readUTF()
            require(npcId !in members) { "Duplicate crew member $npcId" }
            val name = data.readUTF()
            val role = CrewRole.valueOf(data.readUTF())
            val competence = data.readInt()
            val loyalty = data.readInt()
            val injurySeverity = data.readInt()
            val status = CrewStatus.valueOf(data.readUTF())
            val affinityCount = data.readInt()
            require(affinityCount in 0..2) { "Invalid crew affinity count" }
            val affinity = linkedMapOf<String, Int>()
            repeat(affinityCount) {
                val playerId = data.readUTF()
                require(playerId == "p1" || playerId == "p2") { "Invalid crew affinity player" }
                require(playerId !in affinity) { "Duplicate crew affinity player" }
                affinity[playerId] = data.readInt()
            }
            members[npcId] = CrewMemberState(
                npcId = npcId,
                name = name,
                role = role,
                competence = competence,
                loyalty = loyalty,
                injurySeverity = injurySeverity,
                status = status,
                playerAffinity = affinity,
            )
        }
        return CrewState(members)
    }

    private fun writeArcState(data: DataOutputStream, arc: ArcState) {
        data.writeUTF(arc.arcId)
        data.writeUTF(arc.islandId)
        data.writeLong(arc.seed)
        data.writeUTF(arc.archetype.name)
        data.writeUTF(arc.phase.name)
        data.writeInt(arc.escalation)

        data.writeInt(arc.sharedFlags.size)
        arc.sharedFlags.sorted().forEach(data::writeUTF)

        data.writeInt(arc.privateClues.size)
        arc.privateClues.toSortedMap().forEach { (playerId, clues) ->
            data.writeUTF(playerId)
            data.writeInt(clues.size)
            clues.sorted().forEach(data::writeUTF)
        }

        data.writeInt(arc.actedThisPhase.size)
        arc.actedThisPhase.sorted().forEach(data::writeUTF)
    }

    private fun readArcState(data: DataInputStream): ArcState {
        val arcId = data.readUTF()
        val islandId = data.readUTF()
        val seed = data.readLong()
        val archetype = ArcArchetype.valueOf(data.readUTF())
        val phase = ArcPhase.valueOf(data.readUTF())
        val escalation = data.readInt()
        require(escalation in 0..10) { "Invalid arc escalation" }

        val sharedCount = data.readInt()
        require(sharedCount in 0..10_000) { "Invalid arc shared flag count" }
        val shared = linkedSetOf<String>()
        repeat(sharedCount) { shared += data.readUTF() }

        val privatePlayerCount = data.readInt()
        require(privatePlayerCount in 0..2) { "Invalid arc private player count" }
        val privateClues = linkedMapOf<String, Set<String>>()
        repeat(privatePlayerCount) {
            val playerId = data.readUTF()
            require(playerId == "p1" || playerId == "p2") { "Invalid arc private player" }
            require(playerId !in privateClues) { "Duplicate arc private player" }
            val clueCount = data.readInt()
            require(clueCount in 0..10_000) { "Invalid arc private clue count" }
            val clues = linkedSetOf<String>()
            repeat(clueCount) { clues += data.readUTF() }
            privateClues[playerId] = clues
        }

        val actedCount = data.readInt()
        require(actedCount in 0..2) { "Invalid arc acted count" }
        val acted = linkedSetOf<String>()
        repeat(actedCount) {
            val playerId = data.readUTF()
            require(playerId == "p1" || playerId == "p2") { "Invalid arc acted player" }
            require(acted.add(playerId)) { "Duplicate arc acted player" }
        }
        return ArcState(
            arcId = arcId,
            islandId = islandId,
            seed = seed,
            archetype = archetype,
            phase = phase,
            sharedFlags = shared,
            privateClues = mapOf(
                "p1" to privateClues["p1"].orEmpty(),
                "p2" to privateClues["p2"].orEmpty(),
            ),
            actedThisPhase = acted,
            escalation = escalation,
        )
    }

    private fun writeCombatState(data: DataOutputStream, combat: CombatState) {
        data.writeInt(combat.round)
        data.writeUTF(combat.status.name)

        data.writeInt(combat.players.size)
        combat.players.toSortedMap().forEach { (key, fighter) ->
            data.writeUTF(key)
            data.writeUTF(fighter.id)
            data.writeUTF(fighter.name)
            data.writeInt(fighter.hp)
            data.writeInt(fighter.maxHp)
        }

        data.writeUTF(combat.enemy.id)
        data.writeUTF(combat.enemy.name)
        data.writeInt(combat.enemy.hp)
        data.writeInt(combat.enemy.maxHp)
        data.writeInt(combat.enemy.attackPower)
        data.writeUTF(combat.telegraph.type.name)
        data.writeUTF(combat.telegraph.targetPlayerId)

        data.writeInt(combat.lockedActions.size)
        combat.lockedActions.toSortedMap().forEach { (playerId, action) ->
            data.writeUTF(playerId)
            data.writeUTF(action.playerId)
            data.writeUTF(action.type.name)
        }
    }

    private fun readCombatState(data: DataInputStream): CombatState {
        val round = data.readInt()
        require(round >= 1) { "Invalid combat round" }
        val status = CombatStatus.valueOf(data.readUTF())

        val playerCount = data.readInt()
        require(playerCount in 0..4) { "Invalid combat player count" }
        val players = linkedMapOf<String, Combatant>()
        repeat(playerCount) {
            val key = data.readUTF()
            val fighter = Combatant(
                id = data.readUTF(),
                name = data.readUTF(),
                hp = data.readInt(),
                maxHp = data.readInt(),
            )
            require(key !in players) { "Duplicate combat player" }
            require(fighter.hp in 0..fighter.maxHp && fighter.maxHp > 0) { "Invalid combat player hp" }
            players[key] = fighter
        }

        val enemy = EnemyCombatant(
            id = data.readUTF(),
            name = data.readUTF(),
            hp = data.readInt(),
            maxHp = data.readInt(),
            attackPower = data.readInt(),
        )
        require(enemy.hp in 0..enemy.maxHp && enemy.maxHp > 0) { "Invalid combat enemy hp" }
        require(enemy.attackPower >= 0) { "Invalid combat attack power" }
        val telegraph = EnemyTelegraph(
            type = EnemyAttackType.valueOf(data.readUTF()),
            targetPlayerId = data.readUTF(),
        )

        val actionCount = data.readInt()
        require(actionCount in 0..4) { "Invalid combat action count" }
        val actions = linkedMapOf<String, CombatAction>()
        repeat(actionCount) {
            val key = data.readUTF()
            val playerId = data.readUTF()
            val type = CombatActionType.valueOf(data.readUTF())
            require(key == playerId) { "Combat action player mismatch" }
            require(key !in actions) { "Duplicate combat action" }
            actions[key] = CombatAction(playerId, type)
        }
        return CombatState(
            round = round,
            players = players,
            enemy = enemy,
            telegraph = telegraph,
            lockedActions = actions,
            status = status,
        )
    }

    private fun writeVoyage(data: DataOutputStream, voyage: VoyageEncounter) {
        data.writeUTF(voyage.incident.type.name)
        data.writeInt(voyage.incident.severity)
        data.writeLong(voyage.incident.seed)
        val participants = voyage.participants.sorted()
        data.writeInt(participants.size)
        participants.forEach(data::writeUTF)
        val actions = voyage.actions.toSortedMap()
        data.writeInt(actions.size)
        actions.forEach { (playerId, action) ->
            data.writeUTF(playerId)
            data.writeUTF(action.name)
        }
    }

    private fun readVoyage(data: DataInputStream, version: Int): VoyageEncounter {
        val incident = VoyageIncident(
            type = VoyageIncidentType.valueOf(data.readUTF()),
            severity = data.readInt(),
            seed = data.readLong(),
        )
        val participants = if (version >= 11) {
            val participantCount = data.readInt()
            require(participantCount in 2..4) { "Invalid voyage participant count" }
            val decoded = linkedSetOf<String>()
            repeat(participantCount) {
                val playerId = data.readUTF()
                require(playerId in HUMAN_PLAYER_IDS) { "Invalid voyage participant" }
                require(decoded.add(playerId)) { "Duplicate voyage participant" }
            }
            require("p1" in decoded) { "Authoritative P1 must participate in a voyage" }
            decoded
        } else {
            linkedSetOf("p1", "p2")
        }
        val actionCount = data.readInt()
        require(actionCount in 0..participants.size) { "Invalid voyage action count" }
        val actions = linkedMapOf<String, VoyageAction>()
        repeat(actionCount) {
            val playerId = data.readUTF()
            require(playerId in participants) { "Invalid voyage player" }
            require(playerId !in actions) { "Duplicate voyage action" }
            actions[playerId] = VoyageAction.valueOf(data.readUTF())
        }
        return VoyageEncounter(
            incident = incident,
            actions = actions,
            participants = participants,
        )
    }

    private fun writeProfile(data: DataOutputStream, profile: CharacterProfile) {
        data.writeUTF(profile.name)
        data.writeInt(profile.age)
        data.writeUTF(profile.origin)
        data.writeUTF(profile.appearance)
        data.writeUTF(profile.personality)
        data.writeUTF(profile.dream)
        data.writeUTF(profile.fear)
        data.writeUTF(profile.profession)
        data.writeUTF(profile.combatStyle)
        data.writeUTF(profile.background)
        data.writeUTF(profile.motivation)
        data.writeUTF(profile.pirateRelation)
        data.writeUTF(profile.marineRelation)
        data.writeUTF(profile.importantPerson)
        data.writeUTF(profile.defect)
        data.writeInt(profile.evolutionPoints)

        val attributes = profile.attributes.entries.sortedBy { it.key.ordinal }
        data.writeInt(attributes.size)
        attributes.forEach { (attribute, value) ->
            data.writeUTF(attribute.name)
            data.writeInt(value)
        }

        val skills = profile.skills.entries.sortedBy { it.key.ordinal }
        data.writeInt(skills.size)
        skills.forEach { (skill, value) ->
            data.writeUTF(skill.name)
            data.writeInt(value)
        }

        val marks = profile.trainingMarks.sorted()
        data.writeInt(marks.size)
        marks.forEach(data::writeUTF)

        data.writeBoolean(profile.haki.latentHaoshoku)
        val disciplines = profile.haki.disciplines.entries.sortedBy { it.key.ordinal }
        data.writeInt(disciplines.size)
        disciplines.forEach { (type, discipline) ->
            data.writeUTF(type.name)
            data.writeInt(discipline.mastery)
            data.writeInt(discipline.useCount)
        }

        val fruit = profile.devilFruit
        data.writeBoolean(fruit != null)
        if (fruit != null) {
            data.writeUTF(fruit.fruitId)
            data.writeUTF(fruit.category.name)
            data.writeBoolean(fruit.revealedName != null)
            fruit.revealedName?.let(data::writeUTF)
            data.writeInt(fruit.mastery)
            data.writeInt(fruit.useCount)
        }

        val mastery = profile.classMastery
        data.writeBoolean(mastery != null)
        if (mastery != null) {
            writeClassMastery(data, mastery)
        }
    }

    private fun readProfile(data: DataInputStream, version: Int): CharacterProfile {
        val name = data.readUTF()
        val age = data.readInt()
        val origin = data.readUTF()
        val appearance = data.readUTF()
        val personality = data.readUTF()
        val dream = data.readUTF()
        val fear = data.readUTF()
        val profession = data.readUTF()
        val combatStyle = data.readUTF()
        val background = data.readUTF()
        val motivation = data.readUTF()
        val pirateRelation = data.readUTF()
        val marineRelation = data.readUTF()
        val importantPerson = data.readUTF()
        val defect = data.readUTF()
        val evolutionPoints = data.readInt()
        require(evolutionPoints >= 0) { "Invalid evolution points" }

        val attributeCount = data.readInt()
        require(attributeCount in 0..Attribute.entries.size) { "Invalid attribute count" }
        val attributes = linkedMapOf<Attribute, Int>()
        repeat(attributeCount) {
            val attribute = Attribute.valueOf(data.readUTF())
            require(attribute !in attributes) { "Duplicate attribute $attribute" }
            attributes[attribute] = data.readInt()
        }

        val skillCount = data.readInt()
        require(skillCount in 0..Skill.entries.size) { "Invalid skill count" }
        val skills = linkedMapOf<Skill, Int>()
        repeat(skillCount) {
            val skill = Skill.valueOf(data.readUTF())
            require(skill !in skills) { "Duplicate skill $skill" }
            skills[skill] = data.readInt()
        }

        val markCount = data.readInt()
        require(markCount in 0..10_000) { "Invalid training mark count" }
        val marks = linkedSetOf<String>()
        repeat(markCount) { marks += data.readUTF() }

        val haki = if (version >= 3) readHaki(data) else HakiState()
        val devilFruit = if (version >= 3 && data.readBoolean()) readFruit(data) else null
        val classMastery = if (version >= 10 && data.readBoolean()) readClassMastery(data) else null

        return CharacterProfile(
            name = name,
            age = age,
            origin = origin,
            appearance = appearance,
            personality = personality,
            dream = dream,
            fear = fear,
            profession = profession,
            combatStyle = combatStyle,
            background = background,
            motivation = motivation,
            pirateRelation = pirateRelation,
            marineRelation = marineRelation,
            importantPerson = importantPerson,
            defect = defect,
            attributes = attributes,
            skills = skills,
            evolutionPoints = evolutionPoints,
            trainingMarks = marks,
            haki = haki,
            devilFruit = devilFruit,
            classMastery = classMastery,
        )
    }

    private fun writeClassMastery(data: DataOutputStream, mastery: ClassMasteryState) {
        data.writeUTF(mastery.primaryClass.name)

        val levels = mastery.levels.entries.sortedBy { it.key.ordinal }
        data.writeInt(levels.size)
        levels.forEach { (path, level) ->
            data.writeUTF(path.name)
            data.writeInt(level)
        }

        val experience = mastery.experience.entries.sortedBy { it.key.ordinal }
        data.writeInt(experience.size)
        experience.forEach { (path, amount) ->
            data.writeUTF(path.name)
            data.writeLong(amount)
        }
    }

    private fun readClassMastery(data: DataInputStream): ClassMasteryState {
        val primaryClass = ClassPath.valueOf(data.readUTF())

        val levelCount = data.readInt()
        require(levelCount in 0..ClassPath.entries.size) { "Invalid class mastery level count" }
        val levels = linkedMapOf<ClassPath, Int>()
        repeat(levelCount) {
            val path = ClassPath.valueOf(data.readUTF())
            require(path !in levels) { "Duplicate class mastery level $path" }
            val level = data.readInt()
            require(level >= 0) { "Invalid class mastery level" }
            levels[path] = level
        }

        val experienceCount = data.readInt()
        require(experienceCount in 0..ClassPath.entries.size) { "Invalid class mastery experience count" }
        val experience = linkedMapOf<ClassPath, Long>()
        repeat(experienceCount) {
            val path = ClassPath.valueOf(data.readUTF())
            require(path !in experience) { "Duplicate class mastery experience $path" }
            val amount = data.readLong()
            require(amount >= 0L) { "Invalid class mastery experience" }
            experience[path] = amount
        }

        return ClassMasteryState(
            primaryClass = primaryClass,
            levels = levels,
            experience = experience,
        )
    }

    private fun readHaki(data: DataInputStream): HakiState {
        val latent = data.readBoolean()
        val count = data.readInt()
        require(count in 0..HakiType.entries.size) { "Invalid Haki discipline count" }
        val disciplines = linkedMapOf<HakiType, HakiDiscipline>()
        repeat(count) {
            val type = HakiType.valueOf(data.readUTF())
            require(type !in disciplines) { "Duplicate Haki discipline $type" }
            disciplines[type] = HakiDiscipline(
                mastery = data.readInt(),
                useCount = data.readInt(),
            )
        }
        return HakiState(latentHaoshoku = latent, disciplines = disciplines)
    }

    private fun readFruit(data: DataInputStream): DevilFruitState {
        val fruitId = data.readUTF()
        val category = DevilFruitCategory.valueOf(data.readUTF())
        val revealedName = if (data.readBoolean()) data.readUTF() else null
        val mastery = data.readInt()
        val useCount = data.readInt()
        return DevilFruitState(
            fruitId = fruitId,
            category = category,
            revealedName = revealedName,
            mastery = mastery,
            useCount = useCount,
        )
    }

    private val HUMAN_PLAYER_IDS = setOf("p1", "p2", "p3", "p4")
}
