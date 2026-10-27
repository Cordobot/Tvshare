package cordobot.pacificrim.tvshare.capture

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import cordobot.pacificrim.tvshare.MainActivity
import cordobot.pacificrim.tvshare.R
import cordobot.pacificrim.tvshare.StreamInfo
import cordobot.pacificrim.tvshare.TvShareProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Foreground service that keeps mirroring the screen (and optionally the audio) while the user
 * goes back to the launcher and opens any other app.
 */
class ScreenCaptureService : Service() {

    sealed interface State {
        data object Idle : State
        data class Running(
            val addresses: List<String>,
            val port: Int,
            val width: Int,
            val height: Int,
            val audio: Boolean,
            val clients: Int,
        ) : State
        data class Error(val message: String) : State
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: VideoEncoder? = null
    private var audio: AudioCapture? = null
    private var server: StreamServer? = null
    private var discovery: DiscoveryResponder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (projection != null) return START_NOT_STICKY
                startAsForeground()
                try {
                    startCapture(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to start capture", e)
                    mutableState.value = State.Error(e.message ?: e.javaClass.simpleName)
                    shutdown()
                }
            }
            ACTION_STOP -> shutdown()
            // Restarted by the system without a projection token: nothing we can resume.
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = buildNotification(getString(R.string.notification_waiting, "…"))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .build()
    }

    private fun startCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: error("Falta el permiso de captura")
        val targetHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
        val wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, true)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = mpm.getMediaProjection(resultCode, data) ?: error("Permiso de captura denegado")
        projection = mediaProjection
        // Mandatory before createVirtualDisplay on Android 14+; also fires if the user revokes capture.
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === mediaProjection) shutdown()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = realMetrics()
        val (width, height) = captureSize(metrics.widthPixels, metrics.heightPixels, targetHeight)
        val bitRate = if (height >= 1080) 8_000_000 else 4_000_000

        val audioCapture = if (wantAudio && Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            AudioCapture(mediaProjection) { pcm, pts -> server?.sendAudio(pcm, pts) }
        } else {
            null
        }

        val videoEncoder = VideoEncoder(
            width, height, bitRate, FRAME_RATE,
            onConfig = { server?.sendVideoConfig(it) },
            onFrame = { frame, pts, key -> server?.sendVideoFrame(frame, pts, key) },
        )
        encoder = videoEncoder

        val streamInfo = StreamInfo(
            width, height,
            if (audioCapture != null) TvShareProtocol.AUDIO_SAMPLE_RATE else 0,
            if (audioCapture != null) TvShareProtocol.AUDIO_CHANNELS else 0,
        )
        server = StreamServer(
            TvShareProtocol.STREAM_PORT, streamInfo,
            onKeyFrameNeeded = { encoder?.requestKeyFrame() },
            onClientsChanged = { count -> Handler(Looper.getMainLooper()).post { updateClients(count) } },
        ).also { it.start() }

        videoEncoder.start()
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "Tvshare", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            videoEncoder.inputSurface, null, null,
        )

        val audioStarted = Build.VERSION.SDK_INT >= 29 && audioCapture?.start() == true
        if (audioStarted) audio = audioCapture

        val streamPort = server!!.port
        discovery = DiscoveryResponder(this, deviceName(), streamPort).also { it.start() }

        @Suppress("DEPRECATION")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvshare:capture")
            .apply { acquire() }

        mutableState.value = State.Running(
            addresses = localAddresses(),
            port = streamPort,
            width = width,
            height = height,
            audio = audioStarted,
            clients = 0,
        )
        updateClients(0)
    }

    private fun updateClients(count: Int) {
        val running = mutableState.value as? State.Running ?: return
        mutableState.value = running.copy(clients = count)
        val text = if (count > 0) {
            getString(R.string.notification_clients, count)
        } else {
            getString(R.string.notification_waiting, running.addresses.firstOrNull() ?: "?")
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WindowManager::class.java)
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun deviceName(): String =
        (if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) else null)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun shutdown() {
        // Free the ports right away so an immediate restart can bind them again.
        runCatching { server?.stop() }
        runCatching { discovery?.stop() }
        // The rest can block briefly while closing; never on the main thread.
        val toStop = listOf<() -> Unit>(
            { virtualDisplay?.release() },
            { if (Build.VERSION.SDK_INT >= 29) audio?.stop() },
            { encoder?.stop() },
            { projection?.stop() },
            { wakeLock?.takeIf { it.isHeld }?.release() },
        )
        virtualDisplay = null; audio = null; encoder = null; server = null
        discovery = null; projection = null; wakeLock = null
        Thread { toStop.forEach { runCatching(it) } }.start()

        if (mutableState.value !is State.Error) mutableState.value = State.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null) shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val FRAME_RATE = 30

        private const val ACTION_START = "cordobot.pacificrim.tvshare.START"
        private const val ACTION_STOP = "cordobot.pacificrim.tvshare.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_AUDIO = "audio"

        private val mutableState = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = mutableState.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent, height: Int, audio: Boolean) {
            mutableState.value = State.Idle
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_HEIGHT, height)
                .putExtra(EXTRA_AUDIO, audio)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }

        /** Scales the screen down to [targetHeight] keeping the aspect ratio, with even dimensions. */
        fun captureSize(screenWidth: Int, screenHeight: Int, targetHeight: Int): Pair<Int, Int> {
            val landscapeHeight = minOf(screenWidth, screenHeight)
            val scale = minOf(1f, targetHeight.toFloat() / landscapeHeight)
            val w = (screenWidth * scale).toInt() and -2
            val h = (screenHeight * scale).toInt() and -2
            return w to h
        }

        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}
