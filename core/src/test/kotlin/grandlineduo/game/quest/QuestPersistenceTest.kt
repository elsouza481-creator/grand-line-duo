package grandlineduo.game.quest

import grandlineduo.core.hash.CanonicalStateHasher
import grandlineduo.core.model.PlayerState
import grandlineduo.core.model.WorldState
import grandlineduo.core.persistence.WorldStateCodec
import grandlineduo.test.assertEquals
import grandlineduo.test.assertNotEquals
import grandlineduo.test.test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object QuestPersistenceTest {
    fun register() {
        test("quest board round trips through snapshot v10") {
            val quest = definition("ironwake-atoll-2-boss-0-epic")
            val active = QuestProgress(
                definition = quest,
                status = QuestStatus.ACTIVE,
                progress = 1,
                acceptedBy = "p2",
            )
            val state = legacyWorld().copy(
                questBoard = QuestBoardState(
                    generationIndex = 3,
                    offers = mapOf("offer-quest" to definition("offer-quest")),
                    active = mapOf(quest.questId to active),
                    completedQuestIds = setOf("old-win"),
                    failedQuestIds = setOf("old-loss"),
                ),
            )

            val restored = WorldStateCodec.decode(WorldStateCodec.encode(state))

            assertEquals(state, restored)
        }

        test("non-empty quest state participates in authoritative hash") {
            val base = legacyWorld()
            val quest = definition("hash-contract")
            val withQuest = base.copy(
                questBoard = QuestBoardState(
                    generationIndex = 1,
                    offers = mapOf(quest.questId to quest),
                ),
            )

            assertNotEquals(CanonicalStateHasher.hash(base), CanonicalStateHasher.hash(withQuest))
        }

        test("empty default quest board preserves exact legacy canonical hash") {
            assertEquals(
                "ea73b0a8d4ca77206fce3925d537a8c8ae56cee64e5dc891ed1a41e469d82062",
                CanonicalStateHasher.hash(legacyWorld()),
            )
        }

        test("version nine snapshot decodes with empty quest board") {
            val restored = WorldStateCodec.decode(versionNineBytes())

            assertEquals(QuestBoardState(), restored.questBoard)
            assertEquals("Legacy V9", restored.players.getValue("p1").name)
        }
    }

    private fun definition(id: String) = QuestDefinition(
        questId = id,
        islandId = "shells-town",
        title = "Contrato persistente",
        type = QuestType.BOSS,
        rarity = QuestRarity.EPIC,
        issuerFaction = "CIVILIANS",
        targetId = "harbor-enforcer",
        requiredAmount = 2,
        requirement = QuestRequirement(
            factionId = "CIVILIANS",
            minimumFactionStanding = -20,
            minimumTotalBounty = 1_000_000,
            requiredWorldFlag = "marine_alert",
            requiredProfessionContains = "navegador",
            requiredCombatStyleContains = "espada",
        ),
        reward = QuestReward(
            berries = 7_500,
            evolutionPoints = 4,
            itemId = "bandage",
            itemAmount = 2,
            factionId = "CIVILIANS",
            factionStandingDelta = 5,
            worldFlag = "BOSS_CONTRACT_COMPLETE",
        ),
        expiresAfterGeneration = 9,
    )

    private fun legacyWorld() = WorldState(
        campaignId = "legacy-hash",
        lastEventId = 7,
        islandId = "shells-town",
        partyBerries = 1200,
        players = mapOf("p1" to PlayerState("p1", "Kairo", 20, 20, 1000, 9, 10)),
        worldFlags = mapOf("marine_alert" to "2"),
    )

    private fun versionNineBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeInt(9)
            data.writeUTF("v9-no-quests")
            data.writeLong(15)
            data.writeUTF("shells-town")
            data.writeLong(900)
            data.writeInt(0) // government threat
            data.writeInt(0) // faction standings
            data.writeInt(0) // npc relationships
            data.writeBoolean(false) // ship
            data.writeBoolean(false) // active voyage
            data.writeInt(0) // crew members
            data.writeBoolean(false) // active arc
            data.writeBoolean(false) // active combat
            data.writeInt(1) // players
            data.writeUTF("p1")
            data.writeUTF("p1")
            data.writeUTF("Legacy V9")
            data.writeInt(20)
            data.writeInt(20)
            data.writeLong(900)
            data.writeInt(10)
            data.writeInt(10)
            data.writeBoolean(false) // profile
            data.writeInt(0) // flags
        }
    }.toByteArray()
}
