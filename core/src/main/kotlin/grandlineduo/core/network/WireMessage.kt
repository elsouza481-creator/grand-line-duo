package grandlineduo.core.network

import grandlineduo.core.commands.GrantBerriesCommand
import grandlineduo.core.events.CampaignEvent

sealed interface WireMessage {
    data class Hello(val hello: ReconnectHello) : WireMessage
    data class Command(val command: GrantBerriesCommand) : WireMessage
    data class Event(val event: CampaignEvent) : WireMessage
    data class Sync(val plan: SyncPlan) : WireMessage
    data class Error(val message: String) : WireMessage
    data class GameplayCommand(val command: GameplayWireCommand) : WireMessage
    data class Refresh(val hello: ReconnectHello) : WireMessage
}

class WireProtocolException(message: String) : RuntimeException(message)
