package grandlineduo.appshell

enum class ShellMode {
    HOME,
    HOSTING,
    SEARCHING,
    CONNECTED,
    ERROR,
}

data class ShellState(
    val mode: ShellMode,
    val message: String,
    val campaignId: String? = null,
    val remoteHost: String? = null,
    val port: Int? = null,
    val connected: Boolean = false,
) {
    val canCreate: Boolean get() = mode == ShellMode.HOME || mode == ShellMode.ERROR
    val canFind: Boolean get() = mode == ShellMode.HOME || mode == ShellMode.ERROR

    companion object {
        fun initial(): ShellState = ShellState(
            mode = ShellMode.HOME,
            message = "Pronto para zarpar",
        )
    }
}

sealed interface ShellAction {
    data class HostStarted(val campaignId: String, val port: Int) : ShellAction
    data object SearchStarted : ShellAction
    data class ClientConnected(val campaignId: String, val host: String, val port: Int) : ShellAction
    data object HostPeerConnected : ShellAction
    data class Failed(val reason: String) : ShellAction
    data object Reset : ShellAction
}

object ShellStateReducer {
    fun reduce(current: ShellState, action: ShellAction): ShellState = when (action) {
        is ShellAction.HostStarted -> ShellState(
            mode = ShellMode.HOSTING,
            message = "Aventura criada. Aguardando P2…",
            campaignId = action.campaignId,
            port = action.port,
        )

        ShellAction.SearchStarted -> ShellState(
            mode = ShellMode.SEARCHING,
            message = "Procurando aventuras na rede local…",
        )

        is ShellAction.ClientConnected -> ShellState(
            mode = ShellMode.CONNECTED,
            message = "Tripulação conectada",
            campaignId = action.campaignId,
            remoteHost = action.host,
            port = action.port,
            connected = true,
        )

        ShellAction.HostPeerConnected -> current.copy(
            mode = ShellMode.CONNECTED,
            message = "P2 conectado. A tripulação está pronta.",
            connected = true,
        )

        is ShellAction.Failed -> ShellState(
            mode = ShellMode.ERROR,
            message = action.reason,
        )

        ShellAction.Reset -> ShellState.initial()
    }
}
