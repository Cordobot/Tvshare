package cordobot.pacificrim.tvshare

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cordobot.pacificrim.tvshare.capture.ScreenCaptureService

class MainActivity : ComponentActivity() {

    private var pendingHeight = 720
    private var pendingAudio = true

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(this, result.resultCode, data, pendingHeight, pendingAudio)
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Continue even if denied: without RECORD_AUDIO we simply stream video only.
        requestProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvShareTvTheme {
                val state by ScreenCaptureService.state.collectAsState()
                SenderScreen(
                    state = state,
                    audioSupported = Build.VERSION.SDK_INT >= 29,
                    onStart = ::startStreaming,
                    onStop = { ScreenCaptureService.stop(this) },
                    onGoHome = ::goHome,
                )
            }
        }
    }

    private fun startStreaming(height: Int, audio: Boolean) {
        pendingHeight = height
        pendingAudio = audio && Build.VERSION.SDK_INT >= 29
        val permissions = buildList {
            if (pendingAudio) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isEmpty()) requestProjection() else permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+ defaults to sharing a single app, which would stop capturing as soon as the
        // user leaves Tvshare. Ask for the whole screen instead.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    /** Leaves the app; the foreground service keeps streaming whatever is played next. */
    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun TvShareTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7AB8FF),
            background = Color(0xFF0F1626),
            surface = Color(0xFF1B2B4A),
        ),
        content = content,
    )
}

@Composable
private fun SenderScreen(
    state: ScreenCaptureService.State,
    audioSupported: Boolean,
    onStart: (height: Int, audio: Boolean) -> Unit,
    onStop: () -> Unit,
    onGoHome: () -> Unit,
) {
    var height by remember { mutableIntStateOf(720) }
    var audio by remember { mutableStateOf(audioSupported) }
    val primaryFocus = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Tvshare", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Transmite lo que ves en la TV a tu PC", color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(8.dp))

        when (state) {
            is ScreenCaptureService.State.Running -> {
                Text(
                    if (state.clients > 0) "Transmitiendo a ${state.clients} PC" else "Listo. Esperando a la PC…",
                    fontSize = 24.sp, color = Color.White,
                )
                Text(
                    "En la PC abre Tvshare: la TV aparecerá sola. Si no, conecta manualmente a:",
                    color = Color.White.copy(alpha = 0.7f),
                )
                state.addresses.ifEmpty { listOf("(sin red)") }.forEach {
                    Text("$it:${state.port}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text(
                    "${state.width}x${state.height} · " + if (state.audio) "con audio" else "sin audio",
                    color = Color.White.copy(alpha = 0.6f),
                )
                Button(onClick = onGoHome, modifier = Modifier.widthIn(min = 320.dp).focusRequester(primaryFocus).tvFocusRing()) {
                    Text("Ir al inicio y seguir transmitiendo")
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.widthIn(min = 320.dp).tvFocusRing()) {
                    Text("Detener transmisión")
                }
            }
            else -> {
                if (state is ScreenCaptureService.State.Error) {
                    Text("Error: ${state.message}", color = Color(0xFFFF8A80))
                }
                Text("Calidad", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceButton("720p (recomendado)", height == 720) { height = 720 }
                    ChoiceButton("1080p", height == 1080) { height = 1080 }
                }
                if (audioSupported) {
                    Text("Audio", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChoiceButton("Con audio", audio) { audio = true }
                        ChoiceButton("Sin audio", !audio) { audio = false }
                    }
                } else {
                    Text("Este dispositivo (Android < 10) solo puede transmitir video.", color = Color.White.copy(alpha = 0.6f))
                }
                Button(
                    onClick = { onStart(height, audio) },
                    modifier = Modifier.widthIn(min = 320.dp).focusRequester(primaryFocus).tvFocusRing(),
                ) {
                    Text("Iniciar transmisión")
                }
                Text(
                    "Tras aceptar el permiso, pulsa Inicio y abre tu película: la transmisión sigue en segundo plano.\n" +
                        "Nota: apps con DRM (Netflix, Disney+, etc.) pueden mostrarse en negro o sin audio.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                )
            }
        }
    }

    LaunchedEffect(state is ScreenCaptureService.State.Running) {
        runCatching { primaryFocus.requestFocus() }
    }
}

/** Material buttons barely change when focused; on a TV the remote needs a clear highlight. */
private fun Modifier.tvFocusRing(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(50))
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.tvFocusRing()) { Text("✓ $label") }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.tvFocusRing(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
            Text(label)
        }
    }
}
