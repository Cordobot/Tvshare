package cordobot.pacificrim.tvshare.receiver

import cordobot.pacificrim.tvshare.DiscoveryResponse
import cordobot.pacificrim.tvshare.TvShareProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class TvDevice(val name: String, val host: String, val port: Int)

/** Broadcasts a discovery request on every LAN interface and collects the TVs that answer. */
fun discoverTvs(timeoutMs: Int = 1500): List<TvDevice> {
    val found = linkedMapOf<String, TvDevice>()
    DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = 250
        val request = TvShareProtocol.DISCOVERY_REQUEST.toByteArray()
        val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
        val deadline = System.currentTimeMillis() + timeoutMs
        var nextSend = 0L
        val buffer = ByteArray(512)
        while (System.currentTimeMillis() < deadline) {
            if (System.currentTimeMillis() >= nextSend) {
                // Resend a few times: UDP broadcasts are easily lost on Wi-Fi.
                targets.forEach { target ->
                    runCatching { socket.send(DatagramPacket(request, request.size, target, TvShareProtocol.DISCOVERY_PORT)) }
                }
                nextSend = System.currentTimeMillis() + 500
            }
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            }
            val response = DiscoveryResponse.parse(String(packet.data, packet.offset, packet.length)) ?: continue
            val host = packet.address.hostAddress
            found[host] = TvDevice(response.name, host, response.port)
        }
    }
    return found.values.toList()
}

private fun broadcastAddresses(): List<InetAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.interfaceAddresses }
        .mapNotNull { it.broadcast }
        .distinct()
}.getOrDefault(emptyList())
