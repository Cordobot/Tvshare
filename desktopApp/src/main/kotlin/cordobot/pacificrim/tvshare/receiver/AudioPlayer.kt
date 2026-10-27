package cordobot.pacificrim.tvshare.receiver

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/** Plays the TV's PCM audio, dropping data when it falls behind so latency stays low. */
class AudioPlayer(sampleRate: Int, private val channels: Int) : AutoCloseable {
    private val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
    private val bytesPerSecond = sampleRate * channels * 2
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply {
        // ~150 ms device buffer.
        open(format, bytesPerSecond * 15 / 100 / 4 * 4)
        start()
    }
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    @Volatile private var running = true
    @Volatile var muted = false

    private val playThread = thread(name = "tvshare-audio-out", isDaemon = true) {
        while (running) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (muted) java.util.Arrays.fill(chunk, 0)
            line.write(chunk, 0, chunk.size)
        }
    }

    fun play(pcm: ByteArray) {
        // More than ~250 ms queued means we're drifting behind the video: skip ahead.
        if (queue.size > MAX_QUEUED_CHUNKS) queue.clear()
        queue.offer(pcm)
    }

    /** 0.0..1.0 */
    fun setVolume(volume: Float) {
        val gain = runCatching { line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl }.getOrNull() ?: return
        val db = if (volume <= 0.0001f) gain.minimum else (20.0 * kotlin.math.log10(volume.toDouble())).toFloat()
        gain.value = db.coerceIn(gain.minimum, gain.maximum)
    }

    override fun close() {
        running = false
        playThread.join(500)
        line.stop()
        line.close()
    }

    private companion object {
        const val QUEUE_CAPACITY = 64
        const val MAX_QUEUED_CHUNKS = 12 // 20 ms each
    }
}
