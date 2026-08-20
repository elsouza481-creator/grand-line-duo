package grandlineduo.core.network

import grandlineduo.core.commands.GrantBerriesCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.events.EventType
import grandlineduo.core.model.WorldState
import grandlineduo.game.character.CharacterCreationTest
import grandlineduo.test.assertEquals
import grandlineduo.test.test

object WireCodecTest {
    fun register() {
        test("wire codec round trips all foundation message types") {
            val event = CampaignEvent(
                eventId = 1,
                campaignId = "c1",
                eventType = EventType.BERRIES_CHANGED,
                actorId = "p2",
                payloadVersion = 1,
                payload = mapOf("delta" to "50"),
                hostTimestamp = 1001,
                stateHashBefore = "before",
                stateHashAfter = "after",
                commandId = "cmd-1",
                commandFingerprint = "grant-berries|p2|50",
            )
            val messages = listOf<WireMessage>(
                WireMessage.Hello(ReconnectHello(PROTOCOL_VERSION, "c1", 0, "hash-0")),
                WireMessage.Command(GrantBerriesCommand("cmd-1", "p2", 50)),
                WireMessage.Event(event),
                WireMessage.Sync(SyncPlan.UpToDate),
                WireMessage.Sync(SyncPlan.Delta(listOf(event))),
                WireMessage.Sync(SyncPlan.FullSnapshot(WorldState(campaignId = "c1", partyBerries = 50))),
                WireMessage.Error("bad request"),
                WireMessage.GameplayCommand(
                    GameplayWireCommand.CharacterCreate("char-p2", "p2", CharacterCreationTest.validDraft().copy(name = "Namiya"))
                ),
                WireMessage.GameplayCommand(
                    GameplayWireCommand.VoyageAction("voyage-p2", "p2", "PROTECT_SUPPLIES")
                ),
                WireMessage.GameplayCommand(
                    GameplayWireCommand.ArcChoice("arc-p2", "p2", "shadow_authority")
                ),
                WireMessage.GameplayCommand(
                    GameplayWireCommand.InventoryAction("inv-p2", "p2", "EQUIP", "iron_sabre", 1)
                ),
                WireMessage.GameplayCommand(
                    GameplayWireCommand.WorldAction("world-p2", "p2", "SHOP_BUY", "bandage", 2)
                ),
                WireMessage.GameplayCommand(
                    GameplayWireCommand.PowerAction("power-p2", "p2", "HAKI_KENBUNSHOKU")
                ),
            )

            messages.forEach { message ->
                assertEquals(message, WireCodec.decodeFrame(WireCodec.encodeFrame(message)))
            }
        }

        test("wire codec rejects checksum corruption") {
            val bytes = WireCodec.encodeFrame(
                WireMessage.Command(GrantBerriesCommand("cmd-2", "p2", 99))
            )
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x55).toByte()
            var failed = false
            try { WireCodec.decodeFrame(bytes) } catch (_: WireProtocolException) { failed = true }
            assertEquals(true, failed)
        }
    }
}
