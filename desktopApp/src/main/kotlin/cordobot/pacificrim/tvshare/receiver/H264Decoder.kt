package cordobot.pacificrim.tvshare.receiver

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG2_FAST
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.ffmpeg.swscale.SwsFilter
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer

/** A decoded picture as tightly packed BGRA bytes. */
class DecodedFrame(val width: Int, val height: Int, val bgra: ByteArray)

/** Software H.264 decoder (FFmpeg) tuned for low latency. Not thread-safe. */
class H264Decoder : AutoCloseable {
    private val context: AVCodecContext
    private val packet: AVPacket
    private val frame: AVFrame
    private var sws: SwsContext? = null

    private var outWidth = 0
    private var outHeight = 0
    private var outBuffer: BytePointer? = null
    private var outData: PointerPointer<BytePointer>? = null
    private var outStride: IntPointer? = null

    init {
        av_log_set_level(AV_LOG_ERROR)
        val codec = avcodec_find_decoder(AV_CODEC_ID_H264) ?: error("FFmpeg sin decodificador H.264")
        context = avcodec_alloc_context3(codec)
        context.flags(context.flags() or AV_CODEC_FLAG_LOW_DELAY)
        context.flags2(context.flags2() or AV_CODEC_FLAG2_FAST)
        // Slice threading adds no frame delay (frame threading would buffer several frames).
        context.thread_type(FF_THREAD_SLICE)
        context.thread_count(0)
        check(avcodec_open2(context, codec, null as PointerPointer<*>?) >= 0) { "No se pudo abrir el decodificador" }
        packet = av_packet_alloc()
        frame = av_frame_alloc()
    }

    private var pendingConfig: ByteArray? = null

    /** Stores SPS/PPS; they are sent together with the next frame so FFmpeg sees a complete packet. */
    fun configure(config: ByteArray) {
        pendingConfig = config
    }

    /** Feeds one Annex-B access unit and calls [onFrame] for every picture produced. */
    fun decode(frameData: ByteArray, onFrame: (DecodedFrame) -> Unit) {
        val data = pendingConfig?.let { it + frameData } ?: frameData
        pendingConfig = null
        if (av_new_packet(packet, data.size) < 0) return
        packet.data().put(data, 0, data.size)
        val sent = avcodec_send_packet(context, packet)
        av_packet_unref(packet)
        if (sent < 0) return
        while (avcodec_receive_frame(context, frame) >= 0) {
            onFrame(convert())
        }
    }

    private fun convert(): DecodedFrame {
        val w = frame.width()
        val h = frame.height()
        if (w != outWidth || h != outHeight) allocateOutput(w, h)
        sws = sws_getCachedContext(
            sws, w, h, frame.format(), w, h, AV_PIX_FMT_BGRA, SWS_BILINEAR,
            null as SwsFilter?, null as SwsFilter?, null as DoublePointer?,
        )
        sws_scale(sws, frame.data(), frame.linesize(), 0, h, outData, outStride)
        val bytes = ByteArray(w * h * 4)
        outBuffer!!.position(0).get(bytes)
        return DecodedFrame(w, h, bytes)
    }

    private fun allocateOutput(w: Int, h: Int) {
        outBuffer?.close()
        outData?.close()
        outStride?.close()
        outWidth = w
        outHeight = h
        val buffer = BytePointer(w.toLong() * h * 4)
        outBuffer = buffer
        outData = PointerPointer<BytePointer>(buffer, null, null, null)
        outStride = IntPointer(w * 4, 0, 0, 0)
    }

    override fun close() {
        avcodec_free_context(context)
        av_packet_free(packet)
        av_frame_free(frame)
        sws?.let { sws_freeContext(it) }
        outBuffer?.close()
        outData?.close()
        outStride?.close()
    }

    private companion object {
        // FFmpeg's FF_THREAD_SLICE (a macro not exposed by the bindings).
        const val FF_THREAD_SLICE = 2
    }
}
