package cordobot.pacificrim.tvshare.capture

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import cordobot.pacificrim.tvshare.DiscoveryResponse
import cordobot.pacificrim.tvshare.TvShareProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.concurrent.thread

/** Answers the PC's UDP broadcast so the TV shows up in the desktop list automatically. */
class DiscoveryResponder(
    context: Context,
    private val deviceName: String,
    private val streamPort: Int,
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: DatagramSocket? = null

    fun start() {
        // Some devices filter broadcast packets unless a multicast lock is held.
        multicastLock = wifi?.createMulticastLock("tvshare-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val udp = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(TvShareProtocol.DISCOVERY_PORT))
            }
        } catch (e: SocketException) {
            Log.w(TAG, "Discovery disabled: ${e.message}")
            return
        }
        socket = udp
        thread(name = "tvshare-discovery", isDaemon = true) {
            val buffer = ByteArray(256)
            val reply = DiscoveryResponse(deviceName, streamPort).encode().toByteArray()
            while (!udp.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length)
                    if (text.trim() == TvShareProtocol.DISCOVERY_REQUEST) {
                        udp.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                    }
                } catch (e: Exception) {
                    if (!udp.isClosed) Log.w(TAG, "Discovery error", e)
                }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        runCatching { multicastLock?.release() }
    }

    private companion object {
        const val TAG = "DiscoveryResponder"
    }
}
