package cordobot.pacificrim.tvshare

/**
 * Wire protocol shared by the TV sender and the desktop receiver.
 *
 * Stream (TCP, TV is the server):
 *   handshake: MAGIC (int) + VERSION (int)
 *   then a sequence of packets: [type:1][flags:1][ptsUs:8][length:4][payload:length]
 *
 * Discovery (UDP broadcast): the PC sends [DISCOVERY_REQUEST] to [DISCOVERY_PORT] and every
 * TV answers with "[DISCOVERY_RESPONSE]|name|streamPort".
 */
object TvShareProtocol {
    const val STREAM_PORT = 47800
    const val DISCOVERY_PORT = 47801
    const val DISCOVERY_REQUEST = "TVSHARE_DISCOVER_V1"
    const val DISCOVERY_RESPONSE = "TVSHARE_HERE_V1"

    const val MAGIC = 0x54565348 // "TVSH"
    const val VERSION = 1
    const val HEADER_SIZE = 14
    const val MAX_PAYLOAD = 16 * 1024 * 1024

    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNELS = 2
}

enum class PacketType(val id: Int) {
    /** Codec config (SPS/PPS, Annex-B). Must be fed to the decoder before frames. */
    VIDEO_CONFIG(1),
    /** One H.264 access unit, Annex-B. */
    VIDEO_FRAME(2),
    /** Interleaved signed 16-bit little-endian PCM. */
    AUDIO_PCM(3),
    /** [StreamInfo] payload. */
    STREAM_INFO(4);

    companion object {
        fun fromId(id: Int): PacketType? = entries.firstOrNull { it.id == id }
    }
}

object PacketFlags {
    const val KEY_FRAME = 1
}

data class PacketHeader(
    val type: PacketType,
    val flags: Int,
    val ptsUs: Long,
    val length: Int,
) {
    val isKeyFrame: Boolean get() = flags and PacketFlags.KEY_FRAME != 0

    fun encode(): ByteArray {
        val out = ByteArray(TvShareProtocol.HEADER_SIZE)
        out[0] = type.id.toByte()
        out[1] = flags.toByte()
        out.putLong(2, ptsUs)
        out.putInt(10, length)
        return out
    }

    companion object {
        /** Returns null when the header is malformed (unknown type or bad length). */
        fun decode(bytes: ByteArray): PacketHeader? {
            require(bytes.size >= TvShareProtocol.HEADER_SIZE)
            val type = PacketType.fromId(bytes[0].toInt() and 0xFF) ?: return null
            val length = bytes.getInt(10)
            if (length < 0 || length > TvShareProtocol.MAX_PAYLOAD) return null
            return PacketHeader(type, bytes[1].toInt() and 0xFF, bytes.getLong(2), length)
        }
    }
}

/** Sent once per connection so the receiver knows what is coming. */
data class StreamInfo(
    val width: Int,
    val height: Int,
    /** 0 when audio is not being captured. */
    val audioSampleRate: Int,
    val audioChannels: Int,
) {
    val hasAudio: Boolean get() = audioSampleRate > 0 && audioChannels > 0

    fun encode(): ByteArray {
        val out = ByteArray(16)
        out.putInt(0, width)
        out.putInt(4, height)
        out.putInt(8, audioSampleRate)
        out.putInt(12, audioChannels)
        return out
    }

    companion object {
        fun decode(bytes: ByteArray): StreamInfo? {
            if (bytes.size < 16) return null
            return StreamInfo(bytes.getInt(0), bytes.getInt(4), bytes.getInt(8), bytes.getInt(12))
        }
    }
}

data class DiscoveryResponse(val name: String, val port: Int) {
    fun encode(): String = "${TvShareProtocol.DISCOVERY_RESPONSE}|${name.replace("|", " ")}|$port"

    companion object {
        fun parse(text: String): DiscoveryResponse? {
            val parts = text.trim().split("|")
            if (parts.size != 3 || parts[0] != TvShareProtocol.DISCOVERY_RESPONSE) return null
            val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return DiscoveryResponse(parts[1], port)
        }
    }
}

fun ByteArray.putInt(offset: Int, value: Int) {
    for (i in 0 until 4) this[offset + i] = (value ushr (24 - 8 * i)).toByte()
}

fun ByteArray.putLong(offset: Int, value: Long) {
    for (i in 0 until 8) this[offset + i] = (value ushr (56 - 8 * i)).toByte()
}

fun ByteArray.getInt(offset: Int): Int {
    var result = 0
    for (i in 0 until 4) result = (result shl 8) or (this[offset + i].toInt() and 0xFF)
    return result
}

fun ByteArray.getLong(offset: Int): Long {
    var result = 0L
    for (i in 0 until 8) result = (result shl 8) or (this[offset + i].toLong() and 0xFF)
    return result
}
