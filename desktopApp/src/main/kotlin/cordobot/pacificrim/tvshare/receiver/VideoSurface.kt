package cordobot.pacificrim.tvshare.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Hands the most recent decoded frame from the network thread to the UI. Frames the UI never got
 * to show are released immediately so native memory doesn't pile up.
 */
class FrameSlot {
    private val pending = AtomicReference<Bitmap?>(null)

    fun publish(frame: DecodedFrame) {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(frame.width, frame.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        bitmap.installPixels(frame.bgra)
        bitmap.setImmutable()
        pending.getAndSet(bitmap)?.close()
    }

    fun take(): Bitmap? = pending.getAndSet(null)

    fun clear() {
        pending.getAndSet(null)?.close()
    }
}

/** Draws the stream letterboxed to fit the available space. */
@Composable
fun VideoSurface(frames: FrameSlot, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(frames) {
        while (true) {
            val next = withFrameNanos { frames.take() } ?: continue
            val old = current
            current = next
            old?.close()
        }
    }
    DisposableEffect(frames) {
        onDispose {
            current?.close()
            frames.clear()
        }
    }

    Canvas(modifier) {
        val bitmap = current ?: return@Canvas
        if (bitmap.isClosed) return@Canvas
        val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val dstW = (bitmap.width * scale).roundToInt()
        val dstH = (bitmap.height * scale).roundToInt()
        drawImage(
            image = bitmap.asComposeImageBitmap(),
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(((size.width - dstW) / 2).roundToInt(), ((size.height - dstH) / 2).roundToInt()),
            dstSize = IntSize(dstW, dstH),
            filterQuality = FilterQuality.Low,
        )
    }
}
