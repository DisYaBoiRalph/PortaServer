package com.fossylabs.portaserver.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
data class ChatCompletionRequest(
    val model: String = "",
    val messages: List<ChatCompletionMessageDto>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
)

@Serializable
data class ChatCompletionMessageDto(
    val role: String,
    val content: String,
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
)

@Serializable
data class CompletionDelta(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<CompletionChoice>,
)

@Serializable
data class ModelsListResponse(
    val `object`: String = "list",
    val data: List<OpenAIModelDto>,
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
