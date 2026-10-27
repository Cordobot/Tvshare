package cordobot.pacificrim.tvshare.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import kotlin.concurrent.thread

/**
 * Hardware H.264 encoder fed through an input [Surface] (the virtual display renders into it).
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val frameRate: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (data: ByteArray, ptsUs: Long, keyFrame: Boolean) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    lateinit var inputSurface: Surface
        private set

    @Volatile private var running = false
    private var drainThread: Thread? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Keep producing frames on static screens so new PCs get a picture right away.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / frameRate * 3)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        running = true
        drainThread = thread(name = "tvshare-video-encoder", isDaemon = true) { drain() }
    }

    fun requestKeyFrame() {
        if (!running) return
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data, 0, info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onConfig(data)
                    } else {
                        val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        onFrame(data, info.presentationTimeUs, keyFrame)
                    }
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (e: IllegalStateException) {
            if (running) Log.e(TAG, "Encoder failed", e)
        }
    }

    fun stop() {
        running = false
        drainThread?.join(500)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    private companion object {
        const val TAG = "VideoEncoder"
    }
}
