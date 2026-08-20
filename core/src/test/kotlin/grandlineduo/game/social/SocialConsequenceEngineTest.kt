package grandlineduo.game.social

import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test

object SocialConsequenceEngineTest {
    fun register() {
        test("repeatedly helping an NPC can turn acquaintance into ally") {
            var state = SocialState()
            repeat(3) {
                state = SocialConsequenceEngine.apply(
                    state,
                    SocialIncident(SocialIncidentType.HELPED_NPC, npcId = "dockworker-lyra"),
                )
            }
            val relation = state.npcRelationships.getValue("dockworker-lyra")
            assertEquals(60, relation.affinity)
            assertEquals(NpcBond.ALLY, relation.bond)
        }

        test("betraying an ally creates a persistent rival relationship") {
            val initial = SocialState(
                npcRelationships = mapOf(
                    "captain-reno" to NpcRelationship(affinity = 60, bond = NpcBond.ALLY),
                ),
            )
            val result = SocialConsequenceEngine.apply(
                initial,
                SocialIncident(SocialIncidentType.BETRAYED_NPC, npcId = "captain-reno"),
            )
            val relation = result.npcRelationships.getValue("captain-reno")
            assertEquals(-20, relation.affinity)
            assertEquals(NpcBond.RIVAL, relation.bond)
        }

        test("saving a settlement improves its faction standing while attacking faction reduces it") {
            val helped = SocialConsequenceEngine.apply(
                SocialState(),
                SocialIncident(SocialIncidentType.SAVED_SETTLEMENT, factionId = "ARASHI_KINGDOM"),
            )
            val attacked = SocialConsequenceEngine.apply(
                helped,
                SocialIncident(SocialIncidentType.ATTACKED_FACTION, factionId = "ARASHI_KINGDOM"),
            )
            assertEquals(30, helped.factionStanding.getValue("ARASHI_KINGDOM"))
            assertEquals(-10, attacked.factionStanding.getValue("ARASHI_KINGDOM"))
        }

        test("social standing and affinity clamp to minus one hundred through one hundred") {
            var state = SocialState(
                factionStanding = mapOf("MARINES" to 95),
                npcRelationships = mapOf("lyra" to NpcRelationship(95, NpcBond.ALLY)),
            )
            repeat(10) {
                state = SocialConsequenceEngine.apply(
                    state,
                    SocialIncident(SocialIncidentType.HELPED_NPC, npcId = "lyra", factionId = "MARINES"),
                )
            }
            assertEquals(100, state.npcRelationships.getValue("lyra").affinity)
            assertTrue(state.factionStanding.getValue("MARINES") <= 100)

            repeat(20) {
                state = SocialConsequenceEngine.apply(
                    state,
                    SocialIncident(SocialIncidentType.ATTACKED_FACTION, factionId = "MARINES"),
                )
            }
            assertEquals(-100, state.factionStanding.getValue("MARINES"))
        }


        test("NPC capture missing return and death are persistent social events") {
            var state = SocialConsequenceEngine.apply(
                SocialState(),
                SocialIncident(SocialIncidentType.NPC_CAPTURED, npcId = "lyra"),
            )
            assertEquals(NpcStatus.CAPTURED, state.npcRelationships.getValue("lyra").status)

            state = SocialConsequenceEngine.apply(
                state,
                SocialIncident(SocialIncidentType.NPC_MISSING, npcId = "lyra"),
            )
            assertEquals(NpcStatus.MISSING, state.npcRelationships.getValue("lyra").status)

            state = SocialConsequenceEngine.apply(
                state,
                SocialIncident(SocialIncidentType.NPC_RETURNED, npcId = "lyra"),
            )
            assertEquals(NpcStatus.ACTIVE, state.npcRelationships.getValue("lyra").status)

            state = SocialConsequenceEngine.apply(
                state,
                SocialIncident(SocialIncidentType.NPC_DIED, npcId = "lyra"),
            )
            assertEquals(NpcStatus.DEAD, state.npcRelationships.getValue("lyra").status)
            val cannotReturn = SocialConsequenceEngine.apply(
                state,
                SocialIncident(SocialIncidentType.NPC_RETURNED, npcId = "lyra"),
            )
            assertEquals(state, cannotReturn)
        }

        test("dead NPC relationship cannot be modified by ordinary social incidents") {
            val dead = SocialState(
                npcRelationships = mapOf(
                    "reno" to NpcRelationship(affinity = -50, bond = NpcBond.RIVAL, status = NpcStatus.DEAD),
                ),
            )
            val result = SocialConsequenceEngine.apply(
                dead,
                SocialIncident(SocialIncidentType.HELPED_NPC, npcId = "reno"),
            )
            assertEquals(dead, result)
        }
    }
}
