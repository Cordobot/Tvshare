package cordobot.pacificrim.tvshare.capture

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import cordobot.pacificrim.tvshare.TvShareProtocol
import kotlin.concurrent.thread

/**
 * Captures what other apps are playing (Android 10+). The TV keeps playing the sound normally;
 * this only taps a copy. Apps that opt out of playback capture (many DRM streaming apps) are silent.
 */
@RequiresApi(29)
class AudioCapture(
    private val projection: MediaProjection,
    private val onPcm: (data: ByteArray, ptsUs: Long) -> Unit,
) {
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var readThread: Thread? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(TvShareProtocol.AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            TvShareProtocol.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 8))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Playback capture not available", e)
            return false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return false
        }
        record = audioRecord
        audioRecord.startRecording()
        running = true
        readThread = thread(name = "tvshare-audio", isDaemon = true) { read(audioRecord) }
        return true
    }

    private fun read(audioRecord: AudioRecord) {
        while (running) {
            val chunk = ByteArray(CHUNK_BYTES)
            var filled = 0
            while (running && filled < chunk.size) {
                val n = audioRecord.read(chunk, filled, chunk.size - filled)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read failed: $n")
                    return
                }
                filled += n
            }
            if (filled == chunk.size) onPcm(chunk, SystemClock.elapsedRealtimeNanos() / 1000)
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        readThread?.join(500)
        runCatching { record?.release() }
        record = null
    }

    private companion object {
        const val TAG = "AudioCapture"
        // 20 ms of 48 kHz stereo 16-bit audio.
        const val CHUNK_BYTES = TvShareProtocol.AUDIO_SAMPLE_RATE / 50 * TvShareProtocol.AUDIO_CHANNELS * 2
    }
}
