package cordobot.pacificrim.tvshare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedCommonTest {

    @Test
    fun packetHeaderRoundTrip() {
        val header = PacketHeader(PacketType.VIDEO_FRAME, PacketFlags.KEY_FRAME, 1_234_567_890_123L, 65_536)
        val decoded = PacketHeader.decode(header.encode())
        assertEquals(header, decoded)
        assertTrue(decoded!!.isKeyFrame)
    }

    @Test
    fun packetHeaderRejectsUnknownType() {
        val bytes = PacketHeader(PacketType.AUDIO_PCM, 0, 0, 10).encode()
        bytes[0] = 99
        assertNull(PacketHeader.decode(bytes))
    }

    @Test
    fun streamInfoRoundTrip() {
        val info = StreamInfo(1280, 720, 48_000, 2)
        assertEquals(info, StreamInfo.decode(info.encode()))
    }

    @Test
    fun discoveryResponseRoundTrip() {
        val response = DiscoveryResponse("Sala | TV", 47800)
        assertEquals(DiscoveryResponse("Sala   TV", 47800), DiscoveryResponse.parse(response.encode()))
        assertNull(DiscoveryResponse.parse("OTHER|x|1"))
    }
}
