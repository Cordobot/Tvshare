package cordobot.pacificrim.tvshare.capture

import android.util.Log
import cordobot.pacificrim.tvshare.PacketFlags
import cordobot.pacificrim.tvshare.PacketHeader
import cordobot.pacificrim.tvshare.PacketType
import cordobot.pacificrim.tvshare.StreamInfo
import cordobot.pacificrim.tvshare.TvShareProtocol
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * TCP server that fans the encoded stream out to every connected PC.
 * Each client has its own bounded queue so a slow PC never stalls the encoder: when a queue
 * overflows, video is dropped until the next key frame.
 */
class StreamServer(
    private val preferredPort: Int,
    private val streamInfo: StreamInfo,
    private val onKeyFrameNeeded: () -> Unit,
    private val onClientsChanged: (Int) -> Unit,
) {
    private class Packet(val header: PacketHeader, val payload: ByteArray)

    private inner class Client(val socket: Socket) {
        val queue = ArrayBlockingQueue<Packet>(QUEUE_CAPACITY)

        @Volatile var waitingForKeyFrame = true
        @Volatile var closed = false

        fun offer(packet: Packet) {
            if (closed) return
            if (packet.header.type == PacketType.VIDEO_FRAME) {
                if (waitingForKeyFrame && !packet.header.isKeyFrame) return
                waitingForKeyFrame = false
            }
            if (!queue.offer(packet)) {
                // PC can't keep up: drop the backlog and resync on the next key frame.
                queue.clear()
                waitingForKeyFrame = true
                onKeyFrameNeeded()
            }
        }

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 512 * 1024
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
                out.writeInt(TvShareProtocol.MAGIC)
                out.writeInt(TvShareProtocol.VERSION)
                write(out, Packet(PacketHeader(PacketType.STREAM_INFO, 0, 0, 16), streamInfo.encode()))
                out.flush()
                onKeyFrameNeeded()

                while (!closed) {
                    val packet = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    write(out, packet)
                    if (queue.isEmpty()) out.flush()
                }
            } catch (e: IOException) {
                Log.i(TAG, "Client ${socket.remoteSocketAddress} disconnected: ${e.message}")
            } finally {
                close()
            }
        }

        private fun write(out: DataOutputStream, packet: Packet) {
            out.write(packet.header.encode())
            out.write(packet.payload)
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
            if (clients.remove(this)) onClientsChanged(clients.size)
        }
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private var serverSocket: ServerSocket? = null

    private val lock = Any()
    @Volatile private var videoConfig: Packet? = null
    /** Last key frame and the frames after it, cleared on every new key frame. */
    private val gop = ArrayList<Packet>()

    val clientCount: Int get() = clients.size

    /** Port actually in use; differs from the preferred one if that was taken. */
    var port: Int = preferredPort
        private set

    /** Binds the preferred port, or the next free one (the PC learns it through discovery). */
    fun start() {
        val server = (0 until PORT_ATTEMPTS).firstNotNullOfOrNull { offset ->
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(preferredPort + offset))
                }
            } catch (e: IOException) {
                Log.w(TAG, "Port ${preferredPort + offset} busy: ${e.message}")
                null
            }
        } ?: throw IOException("Puertos $preferredPort-${preferredPort + PORT_ATTEMPTS - 1} ocupados")
        port = server.localPort
        serverSocket = server
        thread(name = "tvshare-accept", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                Log.i(TAG, "Client connected: ${socket.remoteSocketAddress}")
                val client = Client(socket)
                synchronized(lock) {
                    // Replay config + current GOP so the PC gets a picture immediately, even if the
                    // TV screen is static and the encoder isn't producing new frames.
                    videoConfig?.let { client.queue.offer(it) }
                    gop.forEach { client.queue.offer(it) }
                    client.waitingForKeyFrame = gop.isEmpty()
                    clients += client
                }
                onClientsChanged(clients.size)
                thread(name = "tvshare-client", isDaemon = true) { client.run() }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        clients.forEach { it.close() }
    }

    fun sendVideoConfig(data: ByteArray) {
        val packet = Packet(PacketHeader(PacketType.VIDEO_CONFIG, 0, 0, data.size), data)
        synchronized(lock) {
            videoConfig = packet
            gop.clear()
            clients.forEach { it.offer(packet) }
        }
    }

    fun sendVideoFrame(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        val flags = if (keyFrame) PacketFlags.KEY_FRAME else 0
        val packet = Packet(PacketHeader(PacketType.VIDEO_FRAME, flags, ptsUs, data.size), data)
        synchronized(lock) {
            if (keyFrame) gop.clear()
            // Only cache a GOP that starts with a key frame; give up on unusually long ones.
            if ((keyFrame || gop.isNotEmpty()) && gop.size < MAX_GOP_FRAMES) gop += packet else if (!keyFrame) gop.clear()
            clients.forEach { it.offer(packet) }
        }
    }

    fun sendAudio(data: ByteArray, ptsUs: Long) {
        if (clients.isEmpty()) return
        val packet = Packet(PacketHeader(PacketType.AUDIO_PCM, 0, ptsUs, data.size), data)
        clients.forEach { it.offer(packet) }
    }

    private companion object {
        const val TAG = "StreamServer"
        const val QUEUE_CAPACITY = 240
        const val PORT_ATTEMPTS = 10
        // I-frame interval is 2 s at 30 fps; keep some headroom.
        const val MAX_GOP_FRAMES = 120
    }
}
