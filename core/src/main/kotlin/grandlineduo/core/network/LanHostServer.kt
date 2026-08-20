package grandlineduo.core.network

import java.io.Closeable
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LanSessionException(message: String) : RuntimeException(message)

class LanHostServer(
    private val hostReplica: HostReplica,
    private val port: Int = 0,
    private val allowedClientId: String? = null,
    private val bindAddress: String = "0.0.0.0",
    private val gameplayCommandHandler: GameplayCommandHandler? = null,
    private val handshakeTimeoutMillis: Int = 5_000,
    private val allowedClientIds: Set<String> = DEFAULT_REMOTE_CLIENT_IDS,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val sessionLock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val activeClients = linkedMapOf<String, Socket>()

    @Volatile
    var boundPort: Int = -1
        private set

    private val effectiveAllowedClientIds: Set<String>
        get() = allowedClientId?.let(::setOf) ?: allowedClientIds

    val activeClientIds: Set<String>
        get() = synchronized(sessionLock) {
            activeClients.entries
                .filter { (_, socket) -> socket.isConnected && !socket.isClosed }
                .mapTo(linkedSetOf()) { it.key }
        }

    val activeClientCount: Int
        get() = activeClientIds.size

    val hasActiveClient: Boolean
        get() = activeClientCount > 0

    fun start() {
        check(running.compareAndSet(false, true)) { "LAN host already started" }
        require(handshakeTimeoutMillis > 0) { "Handshake timeout must be positive" }
        require(effectiveAllowedClientIds.isNotEmpty()) { "At least one remote client must be allowed" }
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName(bindAddress), port))
        serverSocket = server
        boundPort = server.localPort
        acceptThread = thread(name = "gld-lan-accept", isDaemon = true) {
            acceptLoop(server)
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                if (!running.get()) return
                continue
            }
            thread(name = "gld-lan-session", isDaemon = true) { handleSession(socket) }
        }
    }

    private fun handleSession(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = handshakeTimeoutMillis
        var authenticatedPeerId: String? = null
        try {
            val first = WireCodec.read(socket.getInputStream())
            val hello = (first as? WireMessage.Hello)?.hello
                ?: run {
                    WireCodec.write(socket.getOutputStream(), WireMessage.Error("HELLO_REQUIRED"))
                    return
                }
            val peerId = hello.peerId
            if (peerId !in effectiveAllowedClientIds) {
                WireCodec.write(socket.getOutputStream(), WireMessage.Error("PEER_NOT_ALLOWED"))
                return
            }
            authenticatedPeerId = peerId

            synchronized(sessionLock) {
                activeClients[peerId]
                    ?.takeIf { it !== socket && !it.isClosed }
                    ?.close()
                activeClients[peerId] = socket
            }

            val plan = synchronized(hostReplica) { hostReplica.planReconnect(hello) }
            WireCodec.write(socket.getOutputStream(), WireMessage.Sync(plan))

            // The timeout protects only unauthenticated handshakes. An authenticated LAN session
            // may legitimately remain idle while players explore menus, read dialogue or plan moves.
            // Request/response failures are still bounded by the client's own socket timeout.
            socket.soTimeout = 0

            while (running.get() && !socket.isClosed) {
                when (val message = WireCodec.read(socket.getInputStream())) {
                    is WireMessage.Command -> {
                        if (message.command.actorId != peerId) {
                            WireCodec.write(socket.getOutputStream(), WireMessage.Error("ACTOR_NOT_ALLOWED"))
                            continue
                        }
                        val result = synchronized(hostReplica) {
                            hostReplica.submit(message.command, System.currentTimeMillis())
                        }
                        WireCodec.write(socket.getOutputStream(), WireMessage.Event(result.event))
                    }
                    is WireMessage.GameplayCommand -> {
                        if (message.command.actorId != peerId) {
                            WireCodec.write(socket.getOutputStream(), WireMessage.Error("ACTOR_NOT_ALLOWED"))
                            continue
                        }
                        val handler = gameplayCommandHandler
                        if (handler == null) {
                            WireCodec.write(socket.getOutputStream(), WireMessage.Error("GAMEPLAY_NOT_AVAILABLE"))
                            continue
                        }
                        val event = try {
                            synchronized(hostReplica) {
                                handler.handle(message.command, System.currentTimeMillis())
                            }
                        } catch (e: IllegalArgumentException) {
                            WireCodec.write(
                                socket.getOutputStream(),
                                WireMessage.Error("INVALID_GAMEPLAY:${e.message ?: "invalid command"}"),
                            )
                            continue
                        } catch (e: IllegalStateException) {
                            WireCodec.write(
                                socket.getOutputStream(),
                                WireMessage.Error("INVALID_GAMEPLAY_STATE:${e.message ?: "invalid state"}"),
                            )
                            continue
                        }
                        WireCodec.write(socket.getOutputStream(), WireMessage.Event(event))
                    }
                    is WireMessage.Refresh -> {
                        if (message.hello.peerId != peerId) {
                            WireCodec.write(socket.getOutputStream(), WireMessage.Error("PEER_NOT_ALLOWED"))
                            continue
                        }
                        val refreshPlan = synchronized(hostReplica) { hostReplica.planReconnect(message.hello) }
                        WireCodec.write(socket.getOutputStream(), WireMessage.Sync(refreshPlan))
                    }
                    else -> WireCodec.write(socket.getOutputStream(), WireMessage.Error("COMMAND_REQUIRED"))
                }
            }
        } catch (_: EOFException) {
            // Normal disconnect.
        } catch (_: SocketException) {
            // Normal disconnect/replacement.
        } catch (e: ProtocolNegotiationException) {
            runCatching { WireCodec.write(socket.getOutputStream(), WireMessage.Error(e.message ?: "NEGOTIATION_FAILED")) }
        } catch (e: WireProtocolException) {
            runCatching { WireCodec.write(socket.getOutputStream(), WireMessage.Error(e.message ?: "WIRE_ERROR")) }
        } finally {
            synchronized(sessionLock) {
                authenticatedPeerId?.let { peerId ->
                    if (activeClients[peerId] === socket) activeClients.remove(peerId)
                }
            }
            runCatching { socket.close() }
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        synchronized(sessionLock) {
            activeClients.values.toList().forEach { socket -> runCatching { socket.close() } }
            activeClients.clear()
        }
        runCatching { serverSocket?.close() }
        acceptThread?.join(1_000)
    }

    companion object {
        val DEFAULT_REMOTE_CLIENT_IDS: Set<String> = setOf("p2", "p3", "p4")
    }
}
