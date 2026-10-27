package cordobot.pacificrim.tvshare.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cordobot.pacificrim.tvshare.StreamInfo
import cordobot.pacificrim.tvshare.TvShareProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.prefs.Preferences

private sealed interface Screen {
    data object Browse : Screen
    data class Connecting(val device: TvDevice) : Screen
    data class Watching(val device: TvDevice, val info: StreamInfo) : Screen
}

private val prefs: Preferences = Preferences.userRoot().node("cordobot/pacificrim/tvshare")

@Composable
fun ReceiverApp(
    autoConnect: String?,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onTitleChange: (String) -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7AB8FF))) {
        var screen by remember { mutableStateOf<Screen>(Screen.Browse) }
        var lastError by remember { mutableStateOf<String?>(null) }
        var client by remember { mutableStateOf<StreamClient?>(null) }
        val frames = remember { FrameSlot() }

        fun connect(device: TvDevice) {
            client?.close()
            lastError = null
            screen = Screen.Connecting(device)
            prefs.put("lastHost", device.host)
            client = StreamClient(
                device.host, device.port,
                onConnected = { info -> screen = Screen.Watching(device, info) },
                onFrame = frames::publish,
                onDisconnected = { error ->
                    lastError = error?.let { "No se pudo conectar con ${device.name}: $it" }
                    screen = Screen.Browse
                    frames.clear()
                },
            ).also { it.start() }
        }

        fun disconnect() {
            client?.close()
            client = null
            screen = Screen.Browse
        }

        DisposableEffect(Unit) { onDispose { client?.close() } }

        LaunchedEffect(Unit) {
            autoConnect?.let { connect(parseDevice(it)) }
        }

        LaunchedEffect(screen) {
            onTitleChange(
                when (val s = screen) {
                    is Screen.Watching -> "Tvshare — ${s.device.name}"
                    else -> "Tvshare"
                },
            )
        }

        Surface(Modifier.fillMaxSize(), color = Color(0xFF0F1626), contentColor = Color.White) {
            when (val s = screen) {
                Screen.Browse -> BrowseScreen(lastError, ::connect)
                is Screen.Connecting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text("Conectando a ${s.device.name} (${s.device.host})…")
                        TextButton(onClick = ::disconnect) { Text("Cancelar") }
                    }
                }
                is Screen.Watching -> PlayerScreen(
                    device = s.device,
                    info = s.info,
                    frames = frames,
                    audio = client?.audioPlayer,
                    fullscreen = fullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                    onDisconnect = ::disconnect,
                )
            }
        }
    }
}

@Composable
private fun BrowseScreen(error: String?, onConnect: (TvDevice) -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<TvDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf(prefs.get("lastHost", "")) }
    var scanRound by remember { mutableIntStateOf(0) }

    // Keep scanning in the background while this screen is visible.
    LaunchedEffect(scanRound) {
        while (true) {
            scanning = true
            devices = withContext(Dispatchers.IO) { runCatching { discoverTvs() }.getOrDefault(emptyList()) }
            scanning = false
            delay(4000)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Tvshare", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Abre Tvshare en la TV, pulsa “Iniciar transmisión” y elige la TV aquí.", color = Color.White.copy(alpha = 0.75f))

        if (error != null) Text(error, color = Color(0xFFFF8A80))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TVs en la red", fontWeight = FontWeight.SemiBold)
            if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }

        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (devices.isEmpty()) {
                Text(
                    "Buscando… Asegúrate de que la TV y la PC estén en la misma red y que el firewall de Windows permita Tvshare.",
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            devices.forEach { device ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1B2B4A),
                    modifier = Modifier.fillMaxWidth().clickable { onConnect(device) },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text("${device.host}:${device.port}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                        }
                        Text("Ver", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            OutlinedButton(onClick = { scope.launch { scanRound++ } }) { Text("Buscar de nuevo") }
        }

        Spacer(Modifier.size(8.dp))
        Text("O conecta por IP (se muestra en la TV)", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = manualHost,
                onValueChange = { manualHost = it.trim() },
                singleLine = true,
                placeholder = { Text("192.168.1.50") },
                modifier = Modifier.width(260.dp),
            )
            Button(
                enabled = manualHost.isNotBlank(),
                onClick = {
                    onConnect(parseDevice(manualHost))
                },
            ) { Text("Conectar") }
        }
    }
}

@Composable
private fun PlayerScreen(
    device: TvDevice,
    info: StreamInfo,
    frames: FrameSlot,
    audio: AudioPlayer?,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    var volume by remember { mutableFloatStateOf(prefs.getFloat("volume", 1f)) }
    var muted by remember { mutableStateOf(false) }

    LaunchedEffect(audio, volume, muted) {
        audio?.setVolume(volume)
        audio?.muted = muted
        prefs.putFloat("volume", volume)
    }
    LaunchedEffect(lastActivity) {
        controlsVisible = true
        delay(3000)
        controlsVisible = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                            lastActivity = System.currentTimeMillis()
                        }
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onToggleFullscreen() }) },
    ) {
        VideoSurface(frames, Modifier.fillMaxSize())

        if (controlsVisible) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(device.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(
                        "${info.width}x${info.height}" + if (info.hasAudio) " · audio" else " · sin audio",
                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f),
                    )
                }
                if (info.hasAudio && audio != null) {
                    TextButton(onClick = { muted = !muted }) { Text(if (muted) "Activar sonido" else "Silenciar") }
                    Slider(value = volume, onValueChange = { volume = it }, modifier = Modifier.width(140.dp))
                }
                TextButton(onClick = onToggleFullscreen) {
                    Text(if (fullscreen) "Salir de pantalla completa" else "Pantalla completa")
                }
                Button(onClick = onDisconnect) { Text("Desconectar") }
            }
        }
    }
}

/** Parses "host" or "host:port" typed by the user. */
private fun parseDevice(address: String): TvDevice {
    val host = address.trim().substringBefore(":")
    val port = address.substringAfter(":", "").toIntOrNull() ?: TvShareProtocol.STREAM_PORT
    return TvDevice(host, host, port)
}
