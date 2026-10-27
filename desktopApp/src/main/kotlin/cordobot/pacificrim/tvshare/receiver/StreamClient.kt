package cordobot.pacificrim.tvshare.receiver

import cordobot.pacificrim.tvshare.PacketHeader
import cordobot.pacificrim.tvshare.PacketType
import cordobot.pacificrim.tvshare.StreamInfo
import cordobot.pacificrim.tvshare.TvShareProtocol
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.concurrent.thread

/**
 * Connects to a TV, decodes video on its own thread and forwards audio to an [AudioPlayer].
 * Callbacks are invoked from the network thread.
 */
class StreamClient(
    private val host: String,
    private val port: Int,
    private val onConnected: (StreamInfo) -> Unit,
    private val onFrame: (DecodedFrame) -> Unit,
    private val onDisconnected: (error: String?) -> Unit,
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var closing = false
    @Volatile var audioPlayer: AudioPlayer? = null
        private set

    fun start() {
        thread(name = "tvshare-receiver", isDaemon = true) { run() }
    }

    private fun run() {
        var error: String? = null
        var decoder: H264Decoder? = null
        try {
            val s = Socket()
            socket = s
            s.tcpNoDelay = true
            s.receiveBufferSize = 1024 * 1024
            s.connect(InetSocketAddress(host, port), 5000)
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
            if (input.readInt() != TvShareProtocol.MAGIC) throw IOException("No es un servidor Tvshare")
            val version = input.readInt()
            if (version != TvShareProtocol.VERSION) throw IOException("Versión incompatible ($version)")

            decoder = H264Decoder()
            val headerBytes = ByteArray(TvShareProtocol.HEADER_SIZE)
            while (!closing) {
                input.readFully(headerBytes)
                val header = PacketHeader.decode(headerBytes) ?: throw IOException("Paquete inválido")
                val payload = ByteArray(header.length)
                input.readFully(payload)
                when (header.type) {
                    PacketType.STREAM_INFO -> {
                        val info = StreamInfo.decode(payload) ?: throw IOException("Info inválida")
                        if (info.hasAudio) {
                            audioPlayer = runCatching { AudioPlayer(info.audioSampleRate, info.audioChannels) }.getOrNull()
                        }
                        onConnected(info)
                    }
                    PacketType.VIDEO_CONFIG -> decoder.configure(payload)
                    PacketType.VIDEO_FRAME -> decoder.decode(payload, onFrame)
                    PacketType.AUDIO_PCM -> audioPlayer?.play(payload)
                }
            }
        } catch (e: Exception) {
            if (!closing) error = describe(e)
        } finally {
            runCatching { socket?.close() }
            runCatching { audioPlayer?.close() }
            audioPlayer = null
            runCatching { decoder?.close() }
            onDisconnected(error)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ConnectException ->
            "la TV no está transmitiendo en $host:$port. Pulsa “Iniciar transmisión” en la TV y usa la IP y el puerto que muestra."
        is SocketTimeoutException, is NoRouteToHostException ->
            "no se pudo llegar a $host. Verifica que la TV y la PC estén en la misma red."
        is UnknownHostException -> "dirección inválida: $host"
        is EOFException, is SocketException -> "la TV cerró la transmisión."
        else -> e.message ?: e.javaClass.simpleName
    }

    fun close() {
        closing = true
        runCatching { socket?.close() }
    }
}
