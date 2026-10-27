package cordobot.pacificrim.tvshare

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cordobot.pacificrim.tvshare.receiver.ReceiverApp

/** Optional `--connect host[:port]` connects straight to a TV (handy for desktop shortcuts). */
fun main(args: Array<String>) = application {
    val autoConnect = args.indexOf("--connect").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val windowState = rememberWindowState(width = 1280.dp, height = 760.dp)
    var title by remember { mutableStateOf("Tvshare") }
    val fullscreen = windowState.placement == WindowPlacement.Fullscreen

    fun toggleFullscreen() {
        windowState.placement = if (fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = title,
        state = windowState,
        onKeyEvent = { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.key == Key.F11 || event.key == Key.F -> { toggleFullscreen(); true }
                event.key == Key.Escape && fullscreen -> { windowState.placement = WindowPlacement.Floating; true }
                else -> false
            }
        },
    ) {
        ReceiverApp(
            autoConnect = autoConnect,
            fullscreen = fullscreen,
            onToggleFullscreen = ::toggleFullscreen,
            onTitleChange = { title = it },
        )
    }
}
