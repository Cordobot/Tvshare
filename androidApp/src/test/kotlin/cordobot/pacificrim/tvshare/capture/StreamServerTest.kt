package cordobot.pacificrim.tvshare.capture

import cordobot.pacificrim.tvshare.StreamInfo
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StreamServerTest {
    private val info = StreamInfo(1280, 720, 0, 0)

    @Test
    fun fallsBackToNextPortWhenPreferredIsBusy() {
        ServerSocket(0).use { busy ->
            val server = StreamServer(busy.localPort, info, onKeyFrameNeeded = {}, onClientsChanged = {})
            server.start()
            try {
                assertNotEquals(busy.localPort, server.port)
                assertEquals(true, server.port in busy.localPort + 1 until busy.localPort + 10)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun portIsReusableRightAfterStop() {
        val first = StreamServer(ServerSocket(0).use { it.localPort }, info, {}, {})
        first.start()
        val port = first.port
        first.stop()
        val second = StreamServer(port, info, {}, {})
        second.start()
        try {
            assertEquals(port, second.port)
        } finally {
            second.stop()
        }
    }
}
