package com.fossylabs.portaserver.server

import com.fossylabs.portaserver.llm.llmRoutes
import com.fossylabs.portaserver.sql.sqlRoutes
import com.fossylabs.portaserver.server.LogLevel
import com.fossylabs.portaserver.server.LogRepository
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Monitoring) {
                onRequestReceived()
                LogRepository.log(LogLevel.INFO, "LLM ${call.request.local.method.value} ${call.request.local.uri}")
                proceed()
            }
            routing { llmRoutes() }
        }.start(wait = false)

        sqlEngine = embeddedServer(CIO, port = sqlPort) {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Monitoring) {
                onRequestReceived()
                LogRepository.log(LogLevel.INFO, "SQL ${call.request.local.method.value} ${call.request.local.uri}")
                proceed()
            }
            routing { sqlRoutes() }
        }.start(wait = false)
    }

    fun stop() {
        llmEngine?.stop(gracePeriodMillis = 500, timeoutMillis = 3_000)
        sqlEngine?.stop(gracePeriodMillis = 500, timeoutMillis = 3_000)
        llmEngine = null
        sqlEngine = null
    }
}
