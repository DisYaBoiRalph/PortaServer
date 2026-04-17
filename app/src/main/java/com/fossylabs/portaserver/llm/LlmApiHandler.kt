package com.fossylabs.portaserver.llm

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val apiJson = Json { ignoreUnknownKeys = true }

fun Route.llmRoutes() {

    get("/v1/models") {
        val loadedModel = LlmInferenceEngine.loadedModel.value
        val data = if (loadedModel != null) {
            listOf(OpenAIModelDto(id = loadedModel.name))
        } else {
            emptyList()
        }
        call.respond(ModelsListResponse(data = data))
    }

    post("/v1/chat/completions") {
        val request = call.receive<ChatCompletionRequest>()

        if (LlmInferenceEngine.loadedModel.value == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to mapOf("message" to "No model loaded", "type" to "server_error"))
            )
            return@post
        }

        val messages = request.messages.map { ChatMessage(it.role, it.content) }
        val maxTokens = request.maxTokens ?: 512
        val modelName = LlmInferenceEngine.loadedModel.value!!.name
        val requestId = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1_000L

        if (request.stream == true) {
            call.respondBytesWriter(
                contentType = ContentType.parse("text/event-stream; charset=utf-8"),
                status = HttpStatusCode.OK,
            ) {
                // Send initial role delta
                val roleDelta = apiJson.encodeToString(
                    ChatCompletionChunk(
                        id = requestId, created = created, model = modelName,
                        choices = listOf(CompletionChoice(delta = CompletionDelta(role = "assistant")))
                    )
                )
                writeStringUtf8("data: $roleDelta\n\n")
                flush()

                LlmInferenceEngine.generate(messages, maxTokens) { token ->
                    val chunk = apiJson.encodeToString(
                        ChatCompletionChunk(
                            id = requestId, created = created, model = modelName,
                            choices = listOf(CompletionChoice(delta = CompletionDelta(content = token)))
                        )
                    )
                    writeStringUtf8("data: $chunk\n\n")
                    flush()
                }

                writeStringUtf8("data: [DONE]\n\n")
                flush()
            }
        } else {
            val sb = StringBuilder()
            LlmInferenceEngine.generate(messages, maxTokens) { sb.append(it) }
            call.respond(
                ChatCompletionResponse(
                    id = requestId,
                    created = created,
                    model = modelName,
                    choices = listOf(
                        CompletionChoice(
                            message = CompletionMessage(role = "assistant", content = sb.toString()),
                            finishReason = "stop",
                        )
                    ),
                )
            )
        }
    }
}

