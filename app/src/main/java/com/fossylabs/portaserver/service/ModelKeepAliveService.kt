package com.fossylabs.portaserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fossylabs.portaserver.MainActivity
import com.fossylabs.portaserver.llm.ModelSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a loaded model visible while the server is stopped.
 *
 * A model can occupy several GB with nothing in the UI saying so and no way to release it
 * short of killing the app. This surfaces it with an Unload action.
 *
 * Only shown while the server is *not* running — [ServerForegroundService]'s notification
 * already covers the model in that case, and two permanent notifications for one model is
 * worse than none. [com.fossylabs.portaserver.PortaServerApp] owns that decision.
 *
 * Ported in spirit from techjarves/mobile-server (Apache-2.0), originally Google AI Edge
 * Gallery's ModelKeepAliveService.
 */
class ModelKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UNLOAD) {
            scope.launch {
                ModelSession.unload(applicationContext)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val modelName = intent?.getStringExtra(EXTRA_MODEL_NAME) ?: "A model"
        createNotificationChannel()
        val notification = buildNotification(modelName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Loaded model",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when a model is held in memory with the server stopped"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(modelName: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val unloadIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ModelKeepAliveService::class.java).apply { action = ACTION_UNLOAD },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Model loaded")
            .setContentText("$modelName is held in memory")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$modelName is held in memory. The server is stopped — unload to free it.")
            )
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Unload", unloadIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_MODEL_NAME = "model_name"
        private const val ACTION_UNLOAD = "com.fossylabs.portaserver.ACTION_UNLOAD_MODEL"
        private const val CHANNEL_ID = "portaserver_model_keepalive_channel"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context, modelName: String) {
            context.startForegroundService(
                Intent(context, ModelKeepAliveService::class.java)
                    .putExtra(EXTRA_MODEL_NAME, modelName)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ModelKeepAliveService::class.java))
        }
    }
}
