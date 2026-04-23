package com.fossylabs.portaserver.server

import android.content.Context
import android.content.Intent
import com.fossylabs.portaserver.service.ServerForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ServerState { STOPPED, RUNNING }

object ServerManager {

    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    fun start(
        context: Context,
        llmPort: Int,
        sqlPort: Int,
        timeoutMs: Long?,
        modelName: String? = null,
    ) {
        val intent = Intent(context, ServerForegroundService::class.java).apply {
            putExtra(ServerForegroundService.EXTRA_LLM_PORT, llmPort)
            putExtra(ServerForegroundService.EXTRA_SQL_PORT, sqlPort)
            putExtra(ServerForegroundService.EXTRA_TIMEOUT_MS, timeoutMs ?: -1L)
            modelName?.takeIf { it.isNotBlank() }?.let {
                putExtra(ServerForegroundService.EXTRA_MODEL_NAME, it)
            }
        }
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, ServerForegroundService::class.java))
    }

    internal fun onServerStarted() {
        _state.value = ServerState.RUNNING
    }

    internal fun onServerStopped() {
        _state.value = ServerState.STOPPED
    }
}
