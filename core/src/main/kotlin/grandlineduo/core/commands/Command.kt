package grandlineduo.core.commands

import grandlineduo.core.model.WorldState

sealed interface Command {
    val commandId: String
    val actorId: String
    fun fingerprint(): String
}

data class GrantBerriesCommand(
    override val commandId: String,
    override val actorId: String,
    val amount: Long,
) : Command {
    override fun fingerprint(): String = "grant-berries|$actorId|$amount"
}

/**
 * Trusted host-side command used after a gameplay rules engine has validated a high-level action.
 * It is intentionally not exposed as a client wire command: clients send intent, the host computes
 * the next authoritative state, and only then emits this command/event.
 */
data class ReplaceWorldStateCommand(
    override val commandId: String,
    override val actorId: String,
    val nextState: WorldState,
    val sourceFingerprint: String,
    val metadata: Map<String, String> = emptyMap(),
) : Command {
    override fun fingerprint(): String = sourceFingerprint
}
