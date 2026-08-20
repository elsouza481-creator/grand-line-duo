package grandlineduo.appshell

import grandlineduo.core.model.WorldState
import grandlineduo.core.network.ClientReplica
import grandlineduo.core.network.HostReplica
import grandlineduo.core.network.LanClientConnection
import grandlineduo.core.network.LanDiscoveryAdvertisement
import grandlineduo.core.network.LanDiscoveryAdvertiser
import grandlineduo.core.network.LanDiscoveryListener
import grandlineduo.core.network.LanHostServer
import grandlineduo.core.network.PROTOCOL_VERSION
import java.io.Closeable
import java.net.InetAddress
import java.util.UUID

/**
 * Thin, platform-neutral coordinator for the Android shell.
 * Gameplay authority remains in the existing core replicas/handlers.
 */
class LanShellSessionCoordinator : Closeable {
    var state: ShellState = ShellState.initial()
        private set

    private var hostServer: LanHostServer? = null
    private var hostAdvertisement: LanDiscoveryAdvertisement? = null
    private var clientConnection: LanClientConnection? = null

    @Synchronized
    fun startHost(
        hostName: String,
        campaignId: String = "gld-${UUID.randomUUID()}",
    ): ShellState {
        closeSessionResources()
        return try {
            val world = WorldState(campaignId = campaignId)
            val server = LanHostServer(HostReplica(world))
            server.start()
            val advertisement = LanDiscoveryAdvertisement(
                protocolVersion = PROTOCOL_VERSION,
                sessionId = UUID.randomUUID().toString(),
                campaignId = campaignId,
                hostName = hostName.ifBlank { "Grand Line Host" }.take(80),
                tcpPort = server.boundPort,
            )
            hostServer = server
            hostAdvertisement = advertisement
            ShellStateReducer.reduce(state, ShellAction.HostStarted(campaignId, server.boundPort)).also {
                state = it
            }
        } catch (e: Exception) {
            closeSessionResources()
            fail("Não foi possível criar a aventura: ${e.message ?: "erro de rede"}")
        }
    }

    @Synchronized
    fun advertiseOnce(
        targetAddress: InetAddress = InetAddress.getByName("255.255.255.255"),
        discoveryPort: Int = 37778,
    ) {
        val advertisement = hostAdvertisement
            ?: throw IllegalStateException("A aventura precisa estar hospedada antes do anúncio")
        LanDiscoveryAdvertiser(targetAddress, discoveryPort).use { it.send(advertisement) }
    }

    @Synchronized
    fun discoverAndJoin(
        timeoutMillis: Int = 4_000,
        bindAddress: String = "0.0.0.0",
        discoveryPort: Int = 37778,
    ): ShellState {
        closeSessionResources()
        state = ShellStateReducer.reduce(state, ShellAction.SearchStarted)
        return try {
            val discovered = LanDiscoveryListener(bindAddress, discoveryPort).use { listener ->
                listener.start()
                listener.receive(timeoutMillis)
            } ?: return fail("Nenhuma aventura compatível foi encontrada nesta rede")

            val advertisement = discovered.advertisement
            val replica = ClientReplica(WorldState(campaignId = advertisement.campaignId))
            val connection = LanClientConnection(
                host = discovered.sourceAddress.hostAddress,
                port = advertisement.tcpPort,
                peerId = "p2",
                replica = replica,
            )
            connection.connect()
            clientConnection = connection
            ShellStateReducer.reduce(
                state,
                ShellAction.ClientConnected(
                    campaignId = advertisement.campaignId,
                    host = discovered.sourceAddress.hostAddress,
                    port = advertisement.tcpPort,
                ),
            ).also { state = it }
        } catch (e: Exception) {
            closeSessionResources()
            fail("Falha ao entrar na aventura: ${e.message ?: "erro de rede"}")
        }
    }

    @Synchronized
    fun refreshHostPeerState(): ShellState {
        val server = hostServer ?: return state
        if (server.hasActiveClient && !state.connected) {
            state = ShellStateReducer.reduce(state, ShellAction.HostPeerConnected)
        }
        return state
    }

    @Synchronized
    fun reset(): ShellState {
        closeSessionResources()
        return ShellStateReducer.reduce(state, ShellAction.Reset).also { state = it }
    }

    private fun fail(message: String): ShellState =
        ShellStateReducer.reduce(state, ShellAction.Failed(message)).also { state = it }

    private fun closeSessionResources() {
        runCatching { clientConnection?.close() }
        runCatching { hostServer?.close() }
        clientConnection = null
        hostServer = null
        hostAdvertisement = null
    }

    override fun close() {
        closeSessionResources()
    }
}
