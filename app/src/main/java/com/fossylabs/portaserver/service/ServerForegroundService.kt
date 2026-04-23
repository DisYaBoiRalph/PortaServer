package com.fossylabs.portaserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fossylabs.portaserver.MainActivity
import com.fossylabs.portaserver.server.InactivityWatcher
import com.fossylabs.portaserver.server.KtorServer
import com.fossylabs.portaserver.server.LogLevel
import com.fossylabs.portaserver.server.LogRepository
import com.fossylabs.portaserver.server.ServerManager
import com.fossylabs.portaserver.sql.SqliteManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class ServerForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inactivityWatcher = InactivityWatcher()
    private var server: KtorServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val llmPort = intent?.getIntExtra(EXTRA_LLM_PORT, DEFAULT_LLM_PORT) ?: DEFAULT_LLM_PORT
        val sqlPort = intent?.getIntExtra(EXTRA_SQL_PORT, DEFAULT_SQL_PORT) ?: DEFAULT_SQL_PORT
        val modelName = intent?.getStringExtra(EXTRA_MODEL_NAME)
        val timeoutMs = intent?.getLongExtra(EXTRA_TIMEOUT_MS, -1L)?.takeIf { it > 0L }

        val notification = buildNotification(llmPort, sqlPort, modelName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        server = KtorServer(llmPort, sqlPort, inactivityWatcher::recordRequest).also { it.start() }
        SqliteManager.configure(getExternalFilesDir(null) ?: filesDir)
        LogRepository.log(LogLevel.INFO, "Server started — LLM :$llmPort  SQL :$sqlPort")
        ServerManager.onServerStarted()

        timeoutMs?.let { timeout ->
            serviceScope.launch {
                inactivityWatcher.watchForInactivity(timeout) { stopSelf() }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        SqliteManager.closeAll()
        LogRepository.log(LogLevel.INFO, "Server stopped")
        ServerManager.onServerStopped()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PortaServer",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Shows when PortaServer is actively hosting"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(llmPort: Int, sqlPort: Int, modelName: String?): Notification {
        val ip = getLocalIpAddress()
        val endpointText = if (ip != null)
            "http://$ip:$llmPort  ·  SQL :$sqlPort"
        else
            "LLM :$llmPort  ·  SQL :$sqlPort"
        val contentText = modelName?.takeIf { it.isNotBlank() }?.let {
            "LLM active: $it"
        } ?: "LLM hosting is active"
        val expandedText = modelName?.takeIf { it.isNotBlank() }?.let {
            "LLM active: $it\n$endpointText"
        } ?: "LLM hosting is active\n$endpointText"
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ServerForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PortaServer hosting")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_LLM_PORT = "llm_port"
        const val EXTRA_SQL_PORT = "sql_port"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"
        const val EXTRA_MODEL_NAME = "model_name"
        const val DEFAULT_LLM_PORT = 8080
        const val DEFAULT_SQL_PORT = 8181
        private const val ACTION_STOP = "com.fossylabs.portaserver.ACTION_STOP"
        private const val CHANNEL_ID = "portaserver_hosting_channel"
        private const val NOTIFICATION_ID = 1

        fun getLocalIpAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
}
