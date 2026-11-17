package app.magnetrail

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import app.magnetrail.shared.TorrentItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the libtorrent session alive while downloading and mirrors its state
 * into a foreground ("dataSync") notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: MagnetrailApp
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchJob: Job? = null
    private var idleJob: Job? = null
    private var lastActiveAt = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        app = application as MagnetrailApp
        Notifications.createChannel(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Magnetrail:download").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        ensureRunning()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watchJob?.cancel()
        idleJob?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun ensureRunning() {
        ServiceCompat.startForeground(
            this,
            Notifications.ID,
            Notifications.build(this, app.engine.items.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        if (!app.engine.running.value) {
            // 先设好限速再启动会话，settings 里会带上
            app.engine.setUploadRateLimit(app.appSettings.uploadLimitBytes)
            app.engine.start(app.saveRoot.absolutePath, app.tempRoot.absolutePath)
            app.engine.setUploadRateLimit(app.appSettings.uploadLimitBytes)
            restoreStoredMagnets()
        }

        if (watchJob == null) {
            watchJob = scope.launch {
                app.engine.items.collectLatest { items -> onItems(items) }
            }
        }
        if (idleJob == null) {
            idleJob = scope.launch { idleLoop() }
        }
    }

    private fun restoreStoredMagnets() {
        val existing = app.engine.items.value.map { it.infoHash }.toHashSet()
        for (link in app.store.magnets()) {
            if (link.infoHash in existing) continue
            app.engine.addMagnet(link, app.saveRoot.absolutePath)
        }
    }

    private fun onItems(items: List<TorrentItem>) {
        val active = items.any { it.isActive }
        if (active) lastActiveAt = System.currentTimeMillis()
        if (active) acquireWakeLock() else releaseWakeLock()

        getSystemService(NotificationManager::class.java)
            ?.notify(Notifications.ID, Notifications.build(this, items))
    }

    private suspend fun idleLoop() {
        while (scope.isActive) {
            delay(5_000)
            val items = app.engine.items.value
            // 有播放器在读本地流时，绝对不能把会话/HTTP 服务停掉（否则正在播的视频会断）
            val streaming = runCatching { app.engine.isStreamingActive() }.getOrDefault(false)
            val active = items.any { it.isActive } || streaming
            if (active) {
                lastActiveAt = System.currentTimeMillis()
                continue
            }
            val idleFor = System.currentTimeMillis() - lastActiveAt
            // nothing running: everything finished, everything paused, or the list is empty
            if (items.isEmpty()) {
                // nothing in the session: only stop when there is nothing left to restore,
                // otherwise try again (a failed restore must not look like "no tasks")
                if (app.store.magnets().isEmpty()) {
                    stopAll()
                    return
                }
                restoreStoredMagnets()
                if (app.engine.items.value.isNotEmpty()) lastActiveAt = System.currentTimeMillis()
                continue
            }
            if (idleFor > IDLE_STOP_MS) {
                // keep the session alive when the user paused tasks: the list must survive
                // a service stop, otherwise paused torrents would vanish from the UI
                stopAll(keepSession = items.any { it.isPaused && !it.isDone })
                return
            }
        }
    }

    private fun stopAll(keepSession: Boolean = false) {
        watchJob?.cancel()
        watchJob = null
        idleJob?.cancel()
        idleJob = null
        releaseWakeLock()
        if (!keepSession) {
            app.engine.shutdown()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
    }

    companion object {
        const val ACTION_STOP = "app.magnetrail.action.STOP"
        private const val IDLE_STOP_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_STOP))
        }
    }
}
