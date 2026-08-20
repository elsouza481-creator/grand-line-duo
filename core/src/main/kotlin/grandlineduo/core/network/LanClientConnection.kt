package grandlineduo.core.network

import grandlineduo.core.commands.GrantBerriesCommand
import grandlineduo.core.events.CampaignEvent
import grandlineduo.core.persistence.SnapshotStore
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket

class LanClientConnection(
    private val host: String,
    private val port: Int,
    private val peerId: String,
    private val replica: ClientReplica,
    private val snapshotStore: SnapshotStore? = null,
) : Closeable {
    private var socket: Socket? = null

    @Synchronized
    fun connect() {
        disconnect()
        val newSocket = Socket()
        try {
            newSocket.connect(InetSocketAddress(host, port), 3_000)
            newSocket.tcpNoDelay = true
            newSocket.soTimeout = 5_000
            WireCodec.write(
                newSocket.getOutputStream(),
                WireMessage.Hello(replica.reconnectHello(peerId)),
            )
            applySyncResponse(WireCodec.read(newSocket.getInputStream()))
            persistReplica()
            socket = newSocket
        } catch (e: Exception) {
            runCatching { newSocket.close() }
            if (e is LanSessionException) throw e
            throw LanSessionException("Unable to connect: ${e.message}")
        }
    }

    @Synchronized
    fun refresh() {
        val active = activeSocket()
        try {
            WireCodec.write(active.getOutputStream(), WireMessage.Refresh(replica.reconnectHello(peerId)))
            applySyncResponse(WireCodec.read(active.getInputStream()))
            persistReplica()
        } catch (e: LanSessionException) {
            throw e
        } catch (e: Exception) {
            disconnect()
            throw LanSessionException("LAN refresh failed: ${e.message}")
        }
    }

    @Synchronized
    fun send(command: GrantBerriesCommand): CampaignEvent = sendAndReceive(WireMessage.Command(command))

    @Synchronized
    fun sendGameplay(command: GameplayWireCommand): CampaignEvent {
        // Pull any host-local action first. This guarantees that the event returned for this command
        // is always directly applicable, even when P1 acted immediately before P2.
        refresh()
        return sendAndReceive(WireMessage.GameplayCommand(command))
    }

    private fun sendAndReceive(message: WireMessage): CampaignEvent {
        val active = activeSocket()
        try {
            WireCodec.write(active.getOutputStream(), message)
            return when (val response = WireCodec.read(active.getInputStream())) {
                is WireMessage.Event -> response.event.also {
                    replica.receive(it)
                    persistReplica()
                }
                is WireMessage.Error -> throw LanSessionException(response.message)
                else -> throw LanSessionException("Unexpected command response")
            }
        } catch (e: LanSessionException) {
            throw e
        } catch (e: Exception) {
            disconnect()
            throw LanSessionException("LAN command failed: ${e.message}")
        }
    }

    private fun applySyncResponse(response: WireMessage) {
        when (response) {
            is WireMessage.Sync -> replica.applySyncPlan(response.plan)
            is WireMessage.Error -> throw LanSessionException(response.message)
            else -> throw LanSessionException("Unexpected handshake/refresh response")
        }
    }


    private fun persistReplica() {
        snapshotStore?.save(replica.state)
    }

    private fun activeSocket(): Socket = socket?.takeIf { it.isConnected && !it.isClosed }
        ?: throw LanSessionException("Client is not connected")

    @Synchronized
    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }

    override fun close() = disconnect()
}
