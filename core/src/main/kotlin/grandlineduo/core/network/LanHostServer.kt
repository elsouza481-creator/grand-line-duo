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
    private val allowedClientId: String = "p2",
    private val bindAddress: String = "0.0.0.0",
    private val gameplayCommandHandler: GameplayCommandHandler? = null,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val sessionLock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var activeClient: Socket? = null

    @Volatile
    var boundPort: Int = -1
        private set

    val hasActiveClient: Boolean
        get() = synchronized(sessionLock) {
            activeClient?.let { it.isConnected && !it.isClosed } == true
        }

    fun start() {
        check(running.compareAndSet(false, true)) { "LAN host already started" }
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
        socket.soTimeout = 5_000
        try {
            val first = WireCodec.read(socket.getInputStream())
            val hello = (first as? WireMessage.Hello)?.hello
                ?: run {
                    WireCodec.write(socket.getOutputStream(), WireMessage.Error("HELLO_REQUIRED"))
                    return
                }
            if (hello.peerId != allowedClientId) {
                WireCodec.write(socket.getOutputStream(), WireMessage.Error("PEER_NOT_ALLOWED"))
                return
            }

            synchronized(sessionLock) {
                activeClient?.takeIf { it !== socket && !it.isClosed }?.close()
                activeClient = socket
            }

            val plan = synchronized(hostReplica) { hostReplica.planReconnect(hello) }
            WireCodec.write(socket.getOutputStream(), WireMessage.Sync(plan))

            while (running.get() && !socket.isClosed) {
                when (val message = WireCodec.read(socket.getInputStream())) {
                    is WireMessage.Command -> {
                        if (message.command.actorId != allowedClientId) {
                            WireCodec.write(socket.getOutputStream(), WireMessage.Error("ACTOR_NOT_ALLOWED"))
                            continue
                        }
                        val result = synchronized(hostReplica) {
                            hostReplica.submit(message.command, System.currentTimeMillis())
                        }
                        WireCodec.write(socket.getOutputStream(), WireMessage.Event(result.event))
                    }
                    is WireMessage.GameplayCommand -> {
                        if (message.command.actorId != allowedClientId) {
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
                        if (message.hello.peerId != allowedClientId) {
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
                if (activeClient === socket) activeClient = null
            }
            runCatching { socket.close() }
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        synchronized(sessionLock) {
            runCatching { activeClient?.close() }
            activeClient = null
        }
        runCatching { serverSocket?.close() }
        acceptThread?.join(1_000)
    }
}
