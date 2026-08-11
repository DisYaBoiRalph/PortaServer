package com.fossylabs.portaserver

import android.app.Application
import com.fossylabs.portaserver.llm.LlmInferenceEngine
import com.fossylabs.portaserver.server.ServerManager
import com.fossylabs.portaserver.server.ServerState
import com.fossylabs.portaserver.service.ModelKeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Owns process-scoped behaviour that outlives any screen.
 *
 * The keep-alive notification has to react to a model being loaded or the server stopping,
 * both of which can happen while no Activity exists — a ViewModel scope is too short-lived
 * to drive it.
 */
class PortaServerApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        observeModelResidency()
    }

    /**
     * Shows the keep-alive notification only when a model is resident *and* the server is
     * stopped. While the server runs its own foreground notification already reports the
     * model, and stacking a second permanent notification for the same thing is worse
     * than showing none.
     */
    private fun observeModelResidency() {
        scope.launch {
            combine(
                LlmInferenceEngine.loadedModel,
                ServerManager.state,
            ) { model, serverState ->
                model?.name?.takeIf { serverState == ServerState.STOPPED }
            }
                .distinctUntilChanged()
                .collect { modelName ->
                    if (modelName != null) {
                        ModelKeepAliveService.start(this@PortaServerApplication, modelName)
                    } else {
                        ModelKeepAliveService.stop(this@PortaServerApplication)
                    }
                }
        }
    }
}
