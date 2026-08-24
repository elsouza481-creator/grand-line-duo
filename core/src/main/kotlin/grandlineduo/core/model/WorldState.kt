package grandlineduo.core.model

import grandlineduo.game.character.CharacterProfile
import grandlineduo.game.arc.ArcState
import grandlineduo.game.crew.CrewState
import grandlineduo.game.combat.CombatState
import grandlineduo.game.duel.DuelState
import grandlineduo.game.quest.QuestBoardState
import grandlineduo.game.social.SocialState
import grandlineduo.game.ship.ShipState
import grandlineduo.game.ship.VoyageEncounter

data class PlayerState(
    val playerId: String,
    val name: String,
    val hp: Int,
    val maxHp: Int,
    val bounty: Long,
    val energy: Int = 10,
    val maxEnergy: Int = 10,
    val profile: CharacterProfile? = null,
)

data class WorldState(
    val campaignId: String,
    val lastEventId: Long = 0,
    val islandId: String = "origin",
    val partyBerries: Long = 0,
    val governmentThreatPoints: Int = 0,
    val socialState: SocialState = SocialState(),
    val shipState: ShipState? = null,
    val activeVoyage: VoyageEncounter? = null,
    val crewState: CrewState = CrewState(),
    val activeArc: ArcState? = null,
    val activeCombat: CombatState? = null,
    val activeDuel: DuelState? = null,
    val questBoard: QuestBoardState = QuestBoardState(),
    val players: Map<String, PlayerState> = emptyMap(),
    val worldFlags: Map<String, String> = emptyMap(),
)
