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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
        val stream = request.stream == true

        // Tool-call syntax only becomes meaningful once the whole reply is parsed, so a
        // tool-enabled request cannot forward raw pieces as they arrive. Incremental
        // tool_calls deltas come later; for now such a request is buffered and flushed as
        // one chunk, which keeps streaming clients working rather than silently dropping
        // the calls they asked for.
        val hasTools = !request.tools.isNullOrEmpty()

        val generation: suspend (
            suspend (String) -> Unit,
            (suspend (List<NativeChatDelta>) -> Unit)?,
        ) -> ChatGeneration = { onToken, onDelta ->
            LlmInferenceEngine.generateChat(
                messagesJson = request.messages.toString(),
                toolsJson = request.tools?.toString(),
                toolChoice = request.toolChoice.toToolChoiceName(),
                parallelToolCalls = request.parallelToolCalls == true,
                maxTokens = request.maxTokens ?: InferenceDefaults.maxTokens,
                temperature = request.temperature ?: InferenceDefaults.temperature,
                topP = request.topP ?: InferenceDefaults.topP,
                onToken = onToken,
                onDelta = onDelta,
            )
        }

        if (stream) {
            call.respondBytesWriter(
                contentType = ContentType.parse("text/event-stream; charset=utf-8"),
                status = HttpStatusCode.OK,
            ) {
                fun chunk(choice: CompletionChoice) = apiJson.encodeToString(
                    ChatCompletionChunk(
                        id = requestId, created = created, model = modelName,
                        choices = listOf(choice),
                    )
                )

                writeStringUtf8("data: ${chunk(CompletionChoice(delta = CompletionDelta(role = "assistant")))}\n\n")
                flush()

                // Without tools the raw pieces are the content and can go out as they
                // arrive. With tools the reply is re-parsed each token instead, because a
                // half-written <tool_call> block is not something a client can act on.
                val result = if (hasTools) {
                    generation({ }) { deltas ->
                        for (delta in deltas) {
                            val toolCalls = delta.toWireDelta()?.let { listOf(it) }
                            if (delta.content.isEmpty() && toolCalls == null) continue
                            val piece = CompletionDelta(
                                content = delta.content.takeIf { it.isNotEmpty() },
                                toolCalls = toolCalls,
                            )
                            writeStringUtf8("data: " + chunk(CompletionChoice(delta = piece)) + "\n\n")
                            flush()
                        }
                    }
                } else {
                    generation({ token ->
                        val piece = CompletionDelta(content = token)
                        writeStringUtf8("data: " + chunk(CompletionChoice(delta = piece)) + "\n\n")
                        flush()
                    }, null)
                }

                writeStringUtf8(
                    "data: ${chunk(CompletionChoice(finishReason = result.finishReason()))}\n\n"
                )
                writeStringUtf8("data: [DONE]\n\n")
                flush()
            }
        } else {
            val result = generation({ }, null)
            call.respondText(
                apiJson.encodeToString(
                    ChatCompletionResponse(
                        id = requestId, created = created, model = modelName,
                        choices = listOf(
                            CompletionChoice(
                                message = CompletionMessage(
                                    role = "assistant",
                                    content = result.content,
                                    toolCalls = result.toolCalls.takeIf { it.isNotEmpty() }?.toWireCalls(),
                                ),
                                finishReason = result.finishReason(),
                            )
                        ),
                    )
                ),
                ContentType.Application.Json,
            )
        }
    }


    // Lets a client index a codebase against the same server. A chat model is a mediocre
    // embedder -- a dedicated embedding GGUF is much better -- but the endpoint existing at
    // all is what makes retrieval features reachable.
    post("/v1/embeddings") {
        val request = call.receive<EmbeddingsRequest>()
        val modelName = call.readyModelName() ?: return@post

        val inputs = when (val input = request.input) {
            is JsonArray -> input.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(input.contentOrNull)
            else -> emptyList()
        }
        if (inputs.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to mapOf(
                    "message" to "input must be a string or an array of strings",
                    "type" to "invalid_request_error",
                )),
            )
            return@post
        }

        // Mirrors the load-time thread default; embedding builds its own short-lived
        // context, so it does not inherit the generation context's thread count.
        val threads = maxOf(1, Runtime.getRuntime().availableProcessors() / 2)
        val vectors = LlmInferenceEngine.embed(inputs, threads)
        call.respondText(
            apiJson.encodeToString(
                EmbeddingsResponse(
                    data = vectors.mapIndexed { index, vector ->
                        EmbeddingData(index = index, embedding = vector.toList())
                    },
                    model = modelName,
                )
            ),
            ContentType.Application.Json,
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

/** OpenAI allows a string or a {type, function:{name}} object; the object form pins one
 * function, which maps to "required" here since the native side takes a mode, not a name. */
private fun JsonElement?.toToolChoiceName(): String? = when (this) {
    null -> null
    is JsonPrimitive -> contentOrNull
    else -> "required"
}

private fun List<NativeToolCall>.toWireCalls(): List<ToolCall> = mapIndexed { index, call ->
    ToolCall(
        // Clients key tool results by this id, so it must be present even when the
        // model format does not carry one of its own.
        id = call.id.ifEmpty { "call_$index" },
        function = ToolCallFunction(name = call.name, arguments = call.arguments),
    )
}

private fun ChatGeneration.finishReason(): String =
    if (toolCalls.isNotEmpty()) "tool_calls" else "stop"

/** Maps one native diff entry to an OpenAI streaming fragment, if it carries a call. */
private fun NativeChatDelta.toWireDelta(): ToolCallDelta? {
    val index = toolCallIndex ?: return null
    return ToolCallDelta(
        index = index,
        id = toolCallId?.takeIf { it.isNotEmpty() },
        // OpenAI sends type and name only on the fragment that opens a call.
        type = "function".takeIf { !toolCallName.isNullOrEmpty() },
        function = ToolCallFunctionDelta(
            name = toolCallName?.takeIf { it.isNotEmpty() },
            arguments = toolCallArgs?.takeIf { it.isNotEmpty() },
        ),
    )
}
