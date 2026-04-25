package com.fossylabs.portaserver.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object DownloadNotifier {
    private const val CHANNEL_ID = "portaserver_downloads"
    private val toastShown = mutableSetOf<Int>()

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Model downloads",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Shows model download progress" }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun notificationsAllowed(context: Context, mgr: NotificationManager): Boolean {
        val runtimePermissionOk =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        return runtimePermissionOk && mgr.areNotificationsEnabled()
    }

    fun update(
        context: Context,
        id: Int,
        title: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        speedBytesPerSec: Long?,
    ) {
        ensureChannel(context)

        val mgr = context.getSystemService(NotificationManager::class.java)
        val notificationsEnabled = notificationsAllowed(context, mgr)

        val pct = if (totalBytes != null && totalBytes > 0L) {
            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else null

        val contentText = buildString {
            if (totalBytes != null) {
                append(formatBytes(downloadedBytes))
                append(" / ")
                append(formatBytes(totalBytes))
            } else {
                append(formatBytes(downloadedBytes))
            }
            speedBytesPerSec?.let { sp ->
                append("  ·  ")
                append(formatBytes(sp))
                append("/s")
            }
        }

        if (!notificationsEnabled) {
            synchronized(toastShown) {
                if (!toastShown.contains(id)) {
                    Toast.makeText(context, "Downloading: $title", Toast.LENGTH_SHORT).show()
                    toastShown.add(id)
                }
            }
            return
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (pct != null) {
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        mgr.notify(id, builder.build())
    }

    fun complete(context: Context, id: Int, title: String) {
        ensureChannel(context)
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (!notificationsAllowed(context, mgr)) {
            // ensure the user saw a toast at least once
            synchronized(toastShown) { if (!toastShown.contains(id)) {
                Toast.makeText(context, "Downloaded: $title", Toast.LENGTH_SHORT).show()
                toastShown.add(id)
            } }
            return
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Download finished")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        mgr.notify(id, builder.build())
    }

    fun cancel(context: Context, id: Int) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.cancel(id)
        synchronized(toastShown) { toastShown.remove(id) }
    }

    private fun formatBytes(b: Long): String = when {
        b >= 1_073_741_824L -> String.format("%.1f GB", b / 1_073_741_824f)
        b >= 1_048_576L -> String.format("%.0f MB", b / 1_048_576f)
        b >= 1_024L -> String.format("%.0f KB", b / 1_024f)
        else -> "$b B"
    }
}
