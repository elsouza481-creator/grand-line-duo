package grandlineduo.appshell

import grandlineduo.game.character.Attribute
import grandlineduo.game.character.CharacterDraft
import grandlineduo.game.character.Skill
import grandlineduo.game.scenario.ScenarioStage
import grandlineduo.game.StormglassPersistenceAdapter
import grandlineduo.test.assertEquals
import grandlineduo.test.assertTrue
import grandlineduo.test.test
import java.nio.file.Files

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

        test("session coordinator exposes authoritative world management actions") {
            val root = Files.createTempDirectory("gld-world-action")
            GameSessionCoordinator(root).use { session ->
                session.startSolo(campaignId = "world-action")
                session.createCharacter(validDraft("Mira"))
                // Market is two tiles west and one north from the guaranteed town spawn.
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
