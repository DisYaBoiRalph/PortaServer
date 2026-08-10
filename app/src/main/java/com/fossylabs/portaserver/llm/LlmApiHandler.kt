package com.fossylabs.portaserver.llm

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Both flags are load-bearing for OpenAI wire compatibility:
//   encodeDefaults  — `object` and `index` carry default values and clients expect them
//                     on every response and chunk. Ktor's ContentNegotiation Json sets
//                     this, so bodies built here must too or they omit those fields.
//   explicitNulls   — the choice type covers both chat and streaming shapes, so its
//                     unused half is null. OpenAI omits null fields rather than sending
//                     `"delta":null`, and strict clients reject the difference.
private val apiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun Route.llmRoutes() {

    get("/health") {
        call.respond(HealthResponse(model = LlmInferenceEngine.loadedModel.value?.name))
    }

    get("/v1/models") {
        val loadedModel = LlmInferenceEngine.loadedModel.value
        val data = if (loadedModel != null) {
            listOf(OpenAIModelDto(id = loadedModel.name))
        } else {
            emptyList()
        }
        call.respond(ModelsListResponse(data = data))
    }

    // Clients that resolve a model before connecting probe this rather than /v1/models.
    get("/v1/models/{modelId}") {
        val modelId = call.parameters["modelId"]
        val loadedModel = LlmInferenceEngine.loadedModel.value
        if (loadedModel == null || loadedModel.name != modelId) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf(
                    "error" to mapOf(
                        "message" to "Model '$modelId' is not loaded",
                        "type" to "invalid_request_error",
                    )
                ),
            )
        } else {
            call.respond(OpenAIModelDto(id = loadedModel.name))
        }
    }

    post("/v1/chat/completions") {
        val request = call.receive<ChatCompletionRequest>()
        val modelName = call.readyModelName() ?: return@post

        val requestId = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1_000L

        call.respondGeneration(
            messages = request.messages.map { ChatMessage(it.role, it.content) },
            maxTokens = request.maxTokens ?: InferenceDefaults.maxTokens,
            temperature = request.temperature ?: InferenceDefaults.temperature,
            topP = request.topP ?: InferenceDefaults.topP,
            stream = request.stream == true,
            // The chat stream opens with a role-only delta before any content.
            primer = apiJson.encodeToString(
                ChatCompletionChunk(
                    id = requestId, created = created, model = modelName,
                    choices = listOf(CompletionChoice(delta = CompletionDelta(role = "assistant"))),
                )
            ),
            encodeChunk = { token ->
                apiJson.encodeToString(
                    ChatCompletionChunk(
                        id = requestId, created = created, model = modelName,
                        choices = listOf(CompletionChoice(delta = CompletionDelta(content = token))),
                    )
                )
            },
            encodeFinal = { text ->
                apiJson.encodeToString(
                    ChatCompletionResponse(
                        id = requestId, created = created, model = modelName,
                        choices = listOf(
                            CompletionChoice(
                                message = CompletionMessage(role = "assistant", content = text),
                                finishReason = "stop",
                            )
                        ),
                    )
                )
            },
        )
    }

    post("/v1/completions") {
        val request = call.receive<CompletionRequest>()
        val modelName = call.readyModelName() ?: return@post

        val requestId = "cmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1_000L

        call.respondGeneration(
            // The legacy API has no roles; the prompt is passed through verbatim so the
            // model's chat template does not wrap a fill-in-the-middle request in turns.
            messages = listOf(ChatMessage(role = "user", content = request.prompt)),
            maxTokens = request.maxTokens ?: InferenceDefaults.maxTokens,
            temperature = request.temperature ?: InferenceDefaults.temperature,
            topP = request.topP ?: InferenceDefaults.topP,
            stream = request.stream == true,
            encodeChunk = { token ->
                apiJson.encodeToString(
                    CompletionResponse(
                        id = requestId, created = created, model = modelName,
                        choices = listOf(TextCompletionChoice(text = token)),
                    )
                )
            },
            encodeFinal = { text ->
                apiJson.encodeToString(
                    CompletionResponse(
                        id = requestId, created = created, model = modelName,
                        choices = listOf(TextCompletionChoice(text = text, finishReason = "stop")),
                    )
                )
            },
        )
    }
}

/**
 * Returns the loaded model's name, or responds with an error and returns null when the
 * server cannot serve a generation right now.
 */
private suspend fun ApplicationCall.readyModelName(): String? {
    val loadedModel = LlmInferenceEngine.loadedModel.value
    if (loadedModel == null) {
        respond(
            HttpStatusCode.ServiceUnavailable,
            mapOf("error" to mapOf("message" to "No model loaded", "type" to "server_error")),
        )
        return null
    }
    if (LlmInferenceEngine.isBusy) {
        // A load in flight is a transient server state; a generation in flight is a
        // capacity limit. Clients back off differently for the two, so distinguish them.
        if (LlmInferenceEngine.isLoading.value) {
            respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to mapOf("message" to "Model is loading", "type" to "server_error")),
            )
        } else {
            response.header(HttpHeaders.RetryAfter, "1")
            respond(
                HttpStatusCode.TooManyRequests,
                mapOf(
                    "error" to mapOf(
                        "message" to "Model is busy with another request",
                        "type" to "rate_limit_error",
                    )
                ),
            )
        }
        return null
    }
    return loadedModel.name
}

/**
 * Runs generation and writes either an SSE stream or a single JSON body.
 *
 * [primer] is an optional SSE event emitted before any token, [encodeChunk] serializes
 * one streamed token, and [encodeFinal] builds the non-streaming body. Callers supply
 * these so the chat and legacy-completion routes share the streaming mechanics without
 * sharing a wire format.
 */
private suspend fun ApplicationCall.respondGeneration(
    messages: List<ChatMessage>,
    maxTokens: Int,
    temperature: Float?,
    topP: Float?,
    stream: Boolean,
    primer: String? = null,
    encodeChunk: (token: String) -> String,
    encodeFinal: (text: String) -> String,
) {
    if (stream) {
        respondBytesWriter(
            contentType = ContentType.parse("text/event-stream; charset=utf-8"),
            status = HttpStatusCode.OK,
        ) {
            if (primer != null) {
                writeStringUtf8("data: $primer\n\n")
                flush()
            }
            LlmInferenceEngine.generate(messages, maxTokens, temperature, topP) { token ->
                writeStringUtf8("data: ${encodeChunk(token)}\n\n")
                flush()
            }
            writeStringUtf8("data: [DONE]\n\n")
            flush()
        }
    } else {
        val sb = StringBuilder()
        LlmInferenceEngine.generate(messages, maxTokens, temperature, topP) { sb.append(it) }
        respondText(encodeFinal(sb.toString()), ContentType.Application.Json)
    }
}
