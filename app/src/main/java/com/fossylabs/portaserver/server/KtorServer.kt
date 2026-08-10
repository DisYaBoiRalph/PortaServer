package com.fossylabs.portaserver.server

import com.fossylabs.portaserver.llm.llmRoutes
import com.fossylabs.portaserver.sql.sqlRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

class KtorServer(
    private val llmPort: Int,
    private val sqlPort: Int,
    private val onRequestReceived: () -> Unit,
) {

    private var llmEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var sqlEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        llmEngine = embeddedServer(CIO, port = llmPort) {
            configure(label = "LLM") { llmRoutes() }
        }.start(wait = false)

        sqlEngine = embeddedServer(CIO, port = sqlPort) {
            configure(label = "SQL") { sqlRoutes() }
        }.start(wait = false)
    }

    fun stop() {
        llmEngine?.stop(gracePeriodMillis = 500, timeoutMillis = 3_000)
        sqlEngine?.stop(gracePeriodMillis = 500, timeoutMillis = 3_000)
        llmEngine = null
        sqlEngine = null
    }

    /** Shared plugin/monitoring setup applied identically to both engines. */
    private fun Application.configure(label: String, routes: Route.() -> Unit) {
        install(ContentNegotiation) { json() }
        intercept(ApplicationCallPipeline.Monitoring) {
            onRequestReceived()
            LogRepository.log(
                LogLevel.INFO,
                "$label ${call.request.local.method.value} ${call.request.local.uri}",
            )
            proceed()
        }
        routing { routes() }
    }
}
