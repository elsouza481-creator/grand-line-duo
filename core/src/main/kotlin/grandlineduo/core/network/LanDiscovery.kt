package grandlineduo.core.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest

private const val DISCOVERY_MAGIC = 0x474C4431 // GLD1
private const val MAX_DISCOVERY_PACKET = 2048

class LanDiscoveryException(message: String) : RuntimeException(message)

data class LanDiscoveryAdvertisement(
    val protocolVersion: Int,
    val sessionId: String,
    val campaignId: String,
    val hostName: String,
    val tcpPort: Int,
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 64) { "Invalid session id" }
        require(campaignId.isNotBlank() && campaignId.length <= 128) { "Invalid campaign id" }
        require(hostName.isNotBlank() && hostName.length <= 80) { "Invalid host name" }
        require(tcpPort in 1..65535) { "Invalid TCP port" }
    }
}

data class DiscoveredLanSession(
    val advertisement: LanDiscoveryAdvertisement,
    val sourceAddress: InetAddress,
)

object LanDiscoveryCodec {
    fun encode(advertisement: LanDiscoveryAdvertisement): ByteArray {
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(advertisement.protocolVersion)
                data.writeUTF(advertisement.sessionId)
                data.writeUTF(advertisement.campaignId)
                data.writeUTF(advertisement.hostName)
                data.writeInt(advertisement.tcpPort)
            }
        }.toByteArray()
        if (payload.size > MAX_DISCOVERY_PACKET - 40) throw LanDiscoveryException("Discovery payload too large")
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(DISCOVERY_MAGIC)
                data.writeInt(payload.size)
                data.write(checksum)
                data.write(payload)
            }
        }.toByteArray()
    }

    fun decode(packet: ByteArray): LanDiscoveryAdvertisement = try {
        if (packet.size > MAX_DISCOVERY_PACKET) throw LanDiscoveryException("Discovery packet too large")
        DataInputStream(ByteArrayInputStream(packet)).use { data ->
            if (data.readInt() != DISCOVERY_MAGIC) throw LanDiscoveryException("Invalid discovery magic")
            val payloadLength = data.readInt()
            if (payloadLength !in 1..(MAX_DISCOVERY_PACKET - 40)) {
                throw LanDiscoveryException("Invalid discovery length")
            }
            val expected = ByteArray(32)
            data.readFully(expected)
            val payload = ByteArray(payloadLength)
            data.readFully(payload)
            if (data.available() != 0) throw LanDiscoveryException("Trailing discovery bytes")
            val actual = MessageDigest.getInstance("SHA-256").digest(payload)
            if (!MessageDigest.isEqual(expected, actual)) throw LanDiscoveryException("Discovery checksum mismatch")

            DataInputStream(ByteArrayInputStream(payload)).use { body ->
                val ad = LanDiscoveryAdvertisement(
                    protocolVersion = body.readInt(),
                    sessionId = body.readUTF(),
                    campaignId = body.readUTF(),
                    hostName = body.readUTF(),
                    tcpPort = body.readInt(),
                )
                if (body.available() != 0) throw LanDiscoveryException("Trailing discovery payload bytes")
                ad
            }
        }
    } catch (e: LanDiscoveryException) {
        throw e
    } catch (e: Exception) {
        throw LanDiscoveryException("Invalid discovery packet: ${e.message}")
    }
}

class LanDiscoveryAdvertiser(
    private val targetAddress: InetAddress = InetAddress.getByName("255.255.255.255"),
    private val targetPort: Int = 37778,
) : Closeable {
    private val socket = DatagramSocket().apply { broadcast = true }

    fun send(advertisement: LanDiscoveryAdvertisement) {
        val bytes = LanDiscoveryCodec.encode(advertisement)
        socket.send(DatagramPacket(bytes, bytes.size, targetAddress, targetPort))
    }

    override fun close() = socket.close()
}

class LanDiscoveryListener(
    private val bindAddress: String = "0.0.0.0",
    private val port: Int = 37778,
) : Closeable {
    private var socket: DatagramSocket? = null

    @Volatile
    var boundPort: Int = -1
        private set

    fun start() {
        check(socket == null) { "Discovery listener already started" }
        val datagram = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(bindAddress), this@LanDiscoveryListener.port))
        }
        socket = datagram
        boundPort = datagram.localPort
    }

    fun receive(timeoutMillis: Int): DiscoveredLanSession? {
        require(timeoutMillis >= 0) { "Invalid discovery timeout" }
        val active = socket ?: throw IllegalStateException("Discovery listener is not started")
        val deadline = System.nanoTime() + timeoutMillis.toLong() * 1_000_000L
        while (!active.isClosed) {
            val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
            if (remainingMillis <= 0) return null
            active.soTimeout = remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            val buffer = ByteArray(MAX_DISCOVERY_PACKET)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                active.receive(packet)
            } catch (_: SocketTimeoutException) {
                return null
            }
            val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val advertisement = try {
                LanDiscoveryCodec.decode(bytes)
            } catch (_: LanDiscoveryException) {
                continue
            }
            if (advertisement.protocolVersion != PROTOCOL_VERSION) continue
            return DiscoveredLanSession(advertisement, packet.address)
        }
        return null
    }

    override fun close() {
        socket?.close()
        socket = null
        boundPort = -1
    }
}
