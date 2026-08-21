package grandlineduo.core.hash

import grandlineduo.core.model.WorldState
import grandlineduo.game.quest.QuestCanonicalState
import java.security.MessageDigest

object CanonicalStateHasher {
    fun hash(state: WorldState): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(canonicalBytes(state)).joinToString("") { "%02x".format(it) }
    }

    fun canonicalBytes(state: WorldState): ByteArray = buildString {
        field("campaignId", state.campaignId)
        field("lastEventId", state.lastEventId.toString())
        field("islandId", state.islandId)
        field("partyBerries", state.partyBerries.toString())
        if (state.governmentThreatPoints != 0) {
            field("governmentThreatPoints", state.governmentThreatPoints.toString())
        }
        if (state.socialState.factionStanding.isNotEmpty() || state.socialState.npcRelationships.isNotEmpty()) {
            field("socialVersion", "1")
            append("factionStanding=").append(state.socialState.factionStanding.size).append(';')
            state.socialState.factionStanding.toSortedMap().forEach { (factionId, standing) ->
                field("factionId", factionId)
                field("factionStanding", standing.toString())
            }
            append("npcRelationships=").append(state.socialState.npcRelationships.size).append(';')
            state.socialState.npcRelationships.toSortedMap().forEach { (npcId, relationship) ->
                field("npcId", npcId)
                field("npcAffinity", relationship.affinity.toString())
                field("npcBond", relationship.bond.name)
                field("npcStatus", relationship.status.name)
            }
        }
        state.shipState?.let { ship ->
            field("shipVersion", "1")
            field("shipId", ship.shipId)
            field("shipName", ship.name)
            field("shipHull", ship.hull.toString())
            field("shipMaxHull", ship.maxHull.toString())
            field("shipSpeed", ship.speed.toString())
            field("shipManeuverability", ship.maneuverability.toString())
            field("shipArtillery", ship.artillery.toString())
            field("shipCapacity", ship.capacity.toString())
            field("shipSupplies", ship.supplies.toString())
            field("shipMaxSupplies", ship.maxSupplies.toString())
            append("shipCompartments=").append(ship.compartments.size).append(';')
            ship.compartments.sortedBy { it.ordinal }.forEach { field("shipCompartment", it.name) }
            append("shipUpgrades=").append(ship.upgrades.size).append(';')
            ship.upgrades.entries.sortedBy { it.key.ordinal }.forEach { (upgrade, level) ->
                field("shipUpgrade", upgrade.name)
                field("shipUpgradeLevel", level.toString())
            }
        }
        if (state.crewState.members.isNotEmpty()) {
            field("crewVersion", "1")
            append("crewMembers=").append(state.crewState.members.size).append(';')
            state.crewState.members.toSortedMap().forEach { (npcId, member) ->
                field("crewNpcId", npcId)
                field("crewName", member.name)
                field("crewRole", member.role.name)
                field("crewCompetence", member.competence.toString())
                field("crewLoyalty", member.loyalty.toString())
                field("crewInjury", member.injurySeverity.toString())
                field("crewStatus", member.status.name)
                append("crewAffinity=").append(member.playerAffinity.size).append(';')
                member.playerAffinity.toSortedMap().forEach { (playerId, affinity) ->
                    field("crewAffinityPlayer", playerId)
                    field("crewAffinityValue", affinity.toString())
                }
            }
        }
        state.activeVoyage?.let { voyage ->
            field("voyageVersion", "1")
            field("voyageIncident", voyage.incident.type.name)
            field("voyageSeverity", voyage.incident.severity.toString())
            field("voyageSeed", voyage.incident.seed.toString())
            append("voyageActions=").append(voyage.actions.size).append(';')
            voyage.actions.toSortedMap().forEach { (playerId, action) ->
                field("voyagePlayer", playerId)
                field("voyageAction", action.name)
            }
        }
        state.activeArc?.let { arc ->
            field("arcVersion", "1")
            field("arcId", arc.arcId)
            field("arcIslandId", arc.islandId)
            field("arcSeed", arc.seed.toString())
            field("arcArchetype", arc.archetype.name)
            field("arcPhase", arc.phase.name)
            field("arcEscalation", arc.escalation.toString())
            append("arcSharedFlags=").append(arc.sharedFlags.size).append(';')
            arc.sharedFlags.sorted().forEach { field("arcSharedFlag", it) }
            append("arcPrivatePlayers=").append(arc.privateClues.size).append(';')
            arc.privateClues.toSortedMap().forEach { (playerId, clues) ->
                field("arcPrivatePlayer", playerId)
                append("arcPrivateClues=").append(clues.size).append(';')
                clues.sorted().forEach { field("arcPrivateClue", it) }
            }
            append("arcActed=").append(arc.actedThisPhase.size).append(';')
            arc.actedThisPhase.sorted().forEach { field("arcActedPlayer", it) }
        }
        state.activeCombat?.let { combat ->
            field("combatVersion", "1")
            field("combatRound", combat.round.toString())
            field("combatStatus", combat.status.name)
            field("combatEnemyId", combat.enemy.id)
            field("combatEnemyName", combat.enemy.name)
            field("combatEnemyHp", combat.enemy.hp.toString())
            field("combatEnemyMaxHp", combat.enemy.maxHp.toString())
            field("combatEnemyAttack", combat.enemy.attackPower.toString())
            field("combatTelegraphType", combat.telegraph.type.name)
            field("combatTelegraphTarget", combat.telegraph.targetPlayerId)
            append("combatPlayers=").append(combat.players.size).append(';')
            combat.players.toSortedMap().forEach { (playerId, fighter) ->
                field("combatPlayerKey", playerId)
                field("combatPlayerId", fighter.id)
                field("combatPlayerName", fighter.name)
                field("combatPlayerHp", fighter.hp.toString())
                field("combatPlayerMaxHp", fighter.maxHp.toString())
            }
            append("combatActions=").append(combat.lockedActions.size).append(';')
            combat.lockedActions.toSortedMap().forEach { (playerId, action) ->
                field("combatActionPlayer", playerId)
                field("combatActionType", action.type.name)
            }
        }

        append(QuestCanonicalState.encode(state.questBoard))

        append("players=").append(state.players.size).append(';')
        state.players.toSortedMap().forEach { (key, player) ->
            field("playerKey", key)
            field("playerId", player.playerId)
            field("name", player.name)
            field("hp", player.hp.toString())
            field("maxHp", player.maxHp.toString())
            field("bounty", player.bounty.toString())
            field("energy", player.energy.toString())
            field("maxEnergy", player.maxEnergy.toString())
            val profile = player.profile
            if (profile != null) {
                field("profileVersion", "1")
                field("profile.name", profile.name)
                field("profile.age", profile.age.toString())
                field("profile.origin", profile.origin)
                field("profile.appearance", profile.appearance)
                field("profile.personality", profile.personality)
                field("profile.dream", profile.dream)
                field("profile.fear", profile.fear)
                field("profile.profession", profile.profession)
                field("profile.combatStyle", profile.combatStyle)
                field("profile.background", profile.background)
                field("profile.motivation", profile.motivation)
                field("profile.pirateRelation", profile.pirateRelation)
                field("profile.marineRelation", profile.marineRelation)
                field("profile.importantPerson", profile.importantPerson)
                field("profile.defect", profile.defect)
                field("profile.evolutionPoints", profile.evolutionPoints.toString())
                append("profile.attributes=").append(profile.attributes.size).append(';')
                profile.attributes.entries.sortedBy { it.key.ordinal }.forEach { (attribute, value) ->
                    field("profile.attribute", attribute.name)
                    field("profile.attribute.value", value.toString())
                }
                append("profile.skills=").append(profile.skills.size).append(';')
                profile.skills.entries.sortedBy { it.key.ordinal }.forEach { (skill, value) ->
                    field("profile.skill", skill.name)
                    field("profile.skill.value", value.toString())
                }
                append("profile.trainingMarks=").append(profile.trainingMarks.size).append(';')
                profile.trainingMarks.sorted().forEach { field("profile.trainingMark", it) }

                if (profile.haki.latentHaoshoku || profile.haki.disciplines.isNotEmpty() || profile.devilFruit != null) {
                    field("profile.powerVersion", "1")
                    if (profile.haki.latentHaoshoku || profile.haki.disciplines.isNotEmpty()) {
                        field("profile.haki.latentHaoshoku", profile.haki.latentHaoshoku.toString())
                        append("profile.haki.disciplines=").append(profile.haki.disciplines.size).append(';')
                        profile.haki.disciplines.entries.sortedBy { it.key.ordinal }.forEach { (type, discipline) ->
                            field("profile.haki.type", type.name)
                            field("profile.haki.mastery", discipline.mastery.toString())
                            field("profile.haki.useCount", discipline.useCount.toString())
                        }
                    }
                    profile.devilFruit?.let { fruit ->
                        field("profile.devilFruit.id", fruit.fruitId)
                        field("profile.devilFruit.category", fruit.category.name)
                        field("profile.devilFruit.revealedName", fruit.revealedName ?: "")
                        field("profile.devilFruit.mastery", fruit.mastery.toString())
                        field("profile.devilFruit.useCount", fruit.useCount.toString())
                    }
                }
            }
        }

        append("worldFlags=").append(state.worldFlags.size).append(';')
        state.worldFlags.toSortedMap().forEach { (key, value) ->
            field("flagKey", key)
            field("flagValue", value)
        }
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.field(name: String, value: String) {
        append(name.length).append(':').append(name)
        append(value.length).append(':').append(value).append(';')
    }
}
