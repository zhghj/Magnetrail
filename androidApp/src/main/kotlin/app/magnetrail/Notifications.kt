package app.magnetrail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.magnetrail.shared.Fmt
import app.magnetrail.shared.TorrentItem

object Notifications {

    const val CHANNEL = "downloads"
    const val ID = 0x4D42

    fun createChannel(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL,
            ctx.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = ctx.getString(R.string.notif_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(ctx: Context, items: List<TorrentItem>): Notification {
        val active = items.filter { it.isActive }
        val totalBytes = items.sumOf { it.totalBytes }
        val doneBytes = items.sumOf { it.downloadedBytes }
        val rate = items.sumOf { it.downloadRate }
        val progress = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0) else 0.0

        val text = when {
            items.isEmpty() -> ctx.getString(R.string.notif_idle)
            active.isEmpty() -> "${items.size} 个任务 · 已完成"
            else -> "${active.size} 个活动任务 · ${Fmt.speed(rate)} · ${Fmt.percent(progress)}"
        }

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(ctx.getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, (progress * 100).toInt(), active.isEmpty())
            .addAction(0, ctx.getString(R.string.notif_stop), stop)
            .build()
    }
}
