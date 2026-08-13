package com.fossylabs.portaserver.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

// ── Model discovery ──────────────────────────────────────────────────────────

data class ModelInfo(
    val path: String,
    val name: String,
    val isLocal: Boolean = true,
    val isRecommended: Boolean = false,
    val downloads: Int? = null,
    val likes: Int? = null,
    val isCorrupted: Boolean = false,
    val pipelineTag: String? = null,
    /** File size in bytes when known; drives the [MemoryGuard] estimate. */
    val sizeBytes: Long? = null,
)

data class DeviceSpecs(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val cpuCores: Int,
    val socModel: String?,
    val hasVulkan: Boolean,
) {
    val totalRamGb: Float get() = totalRamBytes / (1024f * 1024f * 1024f)
}

/** Outcome of a reachability probe run from the app against its own server. */
data class HealthCheckResult(
    val label: String,
    val success: Boolean,
    val message: String,
)

data class ModelTier(
    val maxParamBillion: Float,
    val recommendedQuant: String,
    val description: String,
)

// ── Inference ─────────────────────────────────────────────────────────────────

data class ChatMessage(
    val role: String,
    val content: String,
)

// ── OpenAI-compatible wire types ─────────────────────────────────────────────

/**
 * Messages and tools stay as raw JSON: llama.cpp renders and parses them itself, and a
 * role/content DTO cannot carry `tool_calls` on an assistant turn or `tool_call_id` on a
 * tool result -- both of which agentic clients replay on every subsequent request.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String = "",
    val messages: JsonArray,
    val tools: JsonArray? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
)

@Serializable
data class ToolCallFunction(
    val name: String,
    /** JSON object encoded as a string, as the OpenAI schema specifies. */
    val arguments: String,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<CompletionChoice>,
)

@Serializable
data class CompletionChoice(
    val index: Int = 0,
    val message: CompletionMessage? = null,
    val delta: CompletionDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class CompletionMessage(
    val role: String,
    val content: String,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
)

@Serializable
data class CompletionDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<CompletionChoice>,
)

// Legacy text-completions API. Autocomplete clients (e.g. Continue with
// useLegacyCompletionsEndpoint) call this instead of /v1/chat/completions.
// OpenAI allows `prompt` to be a string or an array; only strings are supported.
@Serializable
data class CompletionRequest(
    val model: String = "",
    val prompt: String,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
)

/** Serves as both the non-streaming body and the streamed chunk, as OpenAI does. */
@Serializable
data class CompletionResponse(
    val id: String,
    val `object`: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<TextCompletionChoice>,
)

@Serializable
data class TextCompletionChoice(
    val index: Int = 0,
    val text: String,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ModelsListResponse(
    val `object`: String = "list",
    val data: List<OpenAIModelDto>,
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val model: String? = null,
)

@Serializable
data class OpenAIModelDto(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0L,
    @SerialName("owned_by") val ownedBy: String = "portaserver",
)

// ── HuggingFace API DTO ───────────────────────────────────────────────────────

@Serializable
data class HuggingFaceModelDto(
    @SerialName("modelId") val modelId: String,
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
    val downloads: Int? = null,
    val likes: Int? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class HuggingFaceLfsInfo(
    val sha256: String? = null,
    val size: Long? = null,
)

@Serializable
data class HuggingFaceFileDto(
    val rfilename: String,
    val size: Long? = null,
    val lfs: HuggingFaceLfsInfo? = null,
)

@Serializable
data class HuggingFaceModelDetailDto(
    @SerialName("modelId") val modelId: String = "",
    val siblings: List<HuggingFaceFileDto> = emptyList(),
)

// ── Native chat bridge DTOs ──────────────────────────────────────────────────
// Shapes emitted by llama_bridge.cpp; see LlamaWrapper.nativeChatPromptInfo and
// nativeParseChatOutput.

@Serializable
data class NativeGrammarTrigger(
    val type: String = "",
    val value: String = "",
    val token: Int = -1,
)

@Serializable
data class NativeChatPromptInfo(
    val prompt: String = "",
    val format: String = "",
    val grammar: String = "",
    val grammarLazy: Boolean = false,
    val grammarTriggers: List<NativeGrammarTrigger> = emptyList(),
    val preservedTokens: List<String> = emptyList(),
    val additionalStops: List<String> = emptyList(),
)

@Serializable
data class NativeToolCall(
    val id: String = "",
    val name: String = "",
    val arguments: String = "",
)

@Serializable
data class NativeParsedChat(
    val content: String = "",
    val reasoningContent: String = "",
    val toolCalls: List<NativeToolCall> = emptyList(),
)

/** Outcome of a chat generation: assistant text plus any structured tool calls. */
data class ChatGeneration(
    val content: String,
    val toolCalls: List<NativeToolCall>,
)

/**
 * Throughput of the last generation. Prompt and generation are reported separately
 * because they scale differently: prompt cost grows with conversation length, which is
 * what agentic clients pay on every step.
 */
data class InferenceStats(
    val promptTokens: Int,
    val promptMs: Long,
    val generatedTokens: Int,
    val generationMs: Long,
    val reusedPromptTokens: Int = 0,
) {
    val promptTokensPerSec: Double
        get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0

    val generatedTokensPerSec: Double
        get() = if (generationMs > 0) generatedTokens * 1000.0 / generationMs else 0.0

    fun summary(): String = buildString {
        append("prompt %d tok in %d ms (%.1f tok/s)".format(promptTokens, promptMs, promptTokensPerSec))
        if (reusedPromptTokens > 0) append(", %d reused".format(reusedPromptTokens))
        append(" | gen %d tok in %d ms (%.1f tok/s)".format(generatedTokens, generationMs, generatedTokensPerSec))
    }
}

/** One incremental step of a streamed reply; see LlamaWrapper.nativeDiffChatOutput. */
@Serializable
data class NativeChatDelta(
    val content: String = "",
    val toolCallIndex: Int? = null,
    val toolCallId: String? = null,
    val toolCallName: String? = null,
    val toolCallArgs: String? = null,
)

/** Streaming tool-call fragment in OpenAI shape: index identifies which call it extends. */
@Serializable
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: ToolCallFunctionDelta? = null,
)

@Serializable
data class ToolCallFunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)

// ── Embeddings ───────────────────────────────────────────────────────────────

/** OpenAI allows `input` to be a string or an array of strings; both are accepted. */
@Serializable
data class EmbeddingsRequest(
    val model: String = "",
    val input: JsonElement,
)

@Serializable
data class EmbeddingData(
    val `object`: String = "embedding",
    val index: Int,
    val embedding: List<Float>,
)

@Serializable
data class EmbeddingsUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class EmbeddingsResponse(
    val `object`: String = "list",
    val data: List<EmbeddingData>,
    val model: String,
    val usage: EmbeddingsUsage = EmbeddingsUsage(),
)

/**
 * Raised when a request's prompt cannot fit the context the model was loaded with.
 *
 * Surfaced as a 400 rather than a 500: the request is the problem, and the caller can act
 * on it by shortening the conversation or the server operator can raise the context.
 */
class PromptTooLongException(
    val promptTokens: Int,
    val contextTokens: Int,
) : Exception(
    "Prompt is $promptTokens tokens but the model was loaded with a $contextTokens-token " +
        "context. Raise 'Context length' in Settings and reload the model, or send less history."
)
