package com.fossylabs.portaserver.llm

import android.util.Log
import com.fossylabs.portaserver.server.LogLevel
import com.fossylabs.portaserver.server.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

object LlmInferenceEngine {

    private const val TAG = "LlmInferenceEngine"

    /** Prompt tokens decoded per llama_decode call; comfortably under any default n_batch. */
    private const val DECODE_BATCH_TOKENS = 256

    private val mutex = Mutex()

    /** Decodes the JSON the native chat bridge returns. */
    private val nativeJson = Json { ignoreUnknownKeys = true }

    private var modelPtr: Long = 0L
    private var ctxPtr: Long = 0L
    private var samplerPtr: Long = 0L
    private var chatTemplatesPtr: Long = 0L

    /** Prompt tokens currently held in the KV cache, for prefix reuse across requests. */
    private var cachedPromptTokens: IntArray = IntArray(0)
    private var nPast: Int = 0

    private val _loadedModel = MutableStateFlow<ModelInfo?>(null)
    val loadedModel: StateFlow<ModelInfo?> = _loadedModel.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastStats = MutableStateFlow<InferenceStats?>(null)
    /** Throughput of the most recent generation; null until one has run. */
    val lastStats: StateFlow<InferenceStats?> = _lastStats.asStateFlow()

    /**
     * True while a load or generation holds the engine. Callers use this to reject work
     * immediately rather than queueing on [mutex] and holding a connection open for the
     * duration of somebody else's generation.
     */
    val isBusy: Boolean get() = mutex.isLocked

    suspend fun loadModel(
        modelPath: String,
        nCtx: Int = 2048,
        nThreads: Int = maxOf(1, Runtime.getRuntime().availableProcessors() / 2),
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
    ) {
        loadModelInternal(
            modelLabel = modelPath,
            nCtx = nCtx,
            nThreads = nThreads,
            temperature = temperature,
            topP = topP,
            nativeModelLoader = { LlamaWrapper.nativeLoadModel(modelPath, nCtx, 0) },
        )
    }

    suspend fun loadModelFromFd(
        fd: Int,
        label: String,
        nCtx: Int = 2048,
        nThreads: Int = maxOf(1, Runtime.getRuntime().availableProcessors() / 2),
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
    ) {
        loadModelInternal(
            modelLabel = label,
            nCtx = nCtx,
            nThreads = nThreads,
            temperature = temperature,
            topP = topP,
            nativeModelLoader = { LlamaWrapper.nativeLoadModelFromFd(fd, nCtx, 0) },
        )
    }

    private suspend fun loadModelInternal(
        modelLabel: String,
        nCtx: Int,
        nThreads: Int,
        temperature: Float,
        topP: Float,
        nativeModelLoader: () -> Long,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _isLoading.value = true
            try {
                releaseNative()

                modelPtr = nativeModelLoader()
                if (modelPtr == 0L) {
                    error(withNativeDetail("Failed to load model: $modelLabel"))
                }

                ctxPtr = LlamaWrapper.nativeNewContext(modelPtr, nCtx, nThreads)
                if (ctxPtr == 0L) {
                    LlamaWrapper.nativeFreeModel(modelPtr)
                    modelPtr = 0L
                    error(withNativeDetail("Failed to create context for: $modelLabel"))
                }

                samplerPtr = LlamaWrapper.nativeNewSampler(
                    temperature, topP, System.currentTimeMillis().toInt()
                )
                if (samplerPtr == 0L) {
                    LlamaWrapper.nativeFreeContext(ctxPtr)
                    ctxPtr = 0L
                    LlamaWrapper.nativeFreeModel(modelPtr)
                    modelPtr = 0L
                    error(withNativeDetail("Failed to create sampler for: $modelLabel"))
                }
                nPast = 0

                // Read once per model: the tool-call format is a property of the GGUF's
                // own template. A model without one still serves plain chat, so a failure
                // here is not fatal.
                chatTemplatesPtr = try {
                    LlamaWrapper.nativeInitChatTemplates(modelPtr)
                } catch (_: Throwable) {
                    0L
                }
                if (chatTemplatesPtr != 0L) {
                    val source = runCatching { LlamaWrapper.nativeChatTemplateSource(chatTemplatesPtr) }.getOrNull()
                    LogRepository.log(LogLevel.INFO, "Chat template: ${source ?: "unknown"}")
                } else {
                    LogRepository.log(LogLevel.WARN, "No chat template found; tool calling is unavailable")
                }

                _loadedModel.value = ModelInfo(
                    path = modelLabel,
                    name = File(modelLabel).name,
                    isLocal = true,
                )
                LogRepository.log(LogLevel.INFO, "Model loaded: ${File(modelLabel).name}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun generate(
        messages: List<ChatMessage>,
        maxTokens: Int = 512,
        temperature: Float? = null,
        topP: Float? = null,
        onToken: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(modelPtr != 0L && ctxPtr != 0L) { "No model loaded" }

            val activeSamplerPtr = if (temperature != null || topP != null) {
                LlamaWrapper.nativeNewSampler(
                    temperature ?: 0.7f,
                    topP ?: 0.9f,
                    System.currentTimeMillis().toInt(),
                )
            } else {
                samplerPtr
            }

            LogRepository.log(LogLevel.INFO, "Generation started (${messages.size} messages, max $maxTokens tokens)")

            val prompt = buildLegacyPrompt(messages)

            LlamaWrapper.nativeKvCacheClear(ctxPtr)
            nPast = 0
            // This path rewrites the cache without recording what it holds, so the chat
            // path must not reuse a prefix against it afterwards.
            cachedPromptTokens = IntArray(0)

            val promptTokens = LlamaWrapper.nativeTokenize(modelPtr, prompt, true, parseSpecial = true)
            requirePromptFits(promptTokens.size)
            val decodeOk = LlamaWrapper.nativeDecode(ctxPtr, promptTokens, nPast)
            check(decodeOk) { "Failed to decode prompt" }
            nPast += promptTokens.size

            try {
            repeat(maxTokens) {
                val nextToken = LlamaWrapper.nativeSample(activeSamplerPtr, ctxPtr)
                if (LlamaWrapper.nativeIsEog(modelPtr, nextToken)) return@withContext

                val piece = LlamaWrapper.nativeTokenToString(modelPtr, nextToken)
                onToken(piece)

                val ok = LlamaWrapper.nativeDecode(ctxPtr, intArrayOf(nextToken), nPast)
                nPast++
                if (!ok) return@withContext
            }
            } finally {
                if (activeSamplerPtr != samplerPtr) LlamaWrapper.nativeFreeSampler(activeSamplerPtr)
            }
        }
    }


    /**
     * Chat generation that understands tools.
     *
     * The prompt is rendered by llama.cpp from the model's own template, so tool
     * definitions land in whatever form that model expects, and the reply is parsed back
     * into structured calls rather than left as `<tool_call>` text in the content.
     *
     * [onToken] still sees raw pieces as they arrive. A caller streaming a tool-enabled
     * request cannot forward them verbatim, because tool-call syntax is only meaningful
     * once parsed -- it should buffer and use the returned [ChatGeneration].
     */
    suspend fun generateChat(
        messagesJson: String,
        toolsJson: String?,
        toolChoice: String?,
        parallelToolCalls: Boolean,
        maxTokens: Int,
        temperature: Float?,
        topP: Float?,
        onToken: suspend (String) -> Unit,
        onDelta: (suspend (List<NativeChatDelta>) -> Unit)? = null,
    ): ChatGeneration = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(modelPtr != 0L && ctxPtr != 0L) { "No model loaded" }
            check(chatTemplatesPtr != 0L) { "Model has no chat template" }

            val statePtr = LlamaWrapper.nativeBuildChatPrompt(
                chatTemplatesPtr,
                messagesJson,
                toolsJson,
                toolChoice,
                parallelToolCalls,
                true,
            )
            if (statePtr == 0L) error(withNativeDetail("Failed to build chat prompt"))

            // Always a per-request sampler here: it carries the grammar derived from this
            // request's tools, so it cannot be shared with the engine-wide one.
            val activeSamplerPtr = LlamaWrapper.nativeNewChatSampler(
                modelPtr,
                statePtr,
                temperature ?: 0.7f,
                topP ?: 0.9f,
                System.currentTimeMillis().toInt(),
            )
            if (activeSamplerPtr == 0L) {
                LlamaWrapper.nativeFreeChatParams(statePtr)
                error(withNativeDetail("Failed to create sampler"))
            }

            try {
                val info = nativeJson.decodeFromString<NativeChatPromptInfo>(
                    LlamaWrapper.nativeChatPromptInfo(statePtr)
                )
                LogRepository.log(
                    LogLevel.INFO,
                    "Chat generation started (format ${info.format}, max $maxTokens tokens)",
                )

                val promptTokens = LlamaWrapper.nativeTokenize(modelPtr, info.prompt, true, parseSpecial = true)

                // llama_decode aborts the process when the prompt does not fit, so this
                // has to be rejected here rather than discovered down in ggml.
                requirePromptFits(promptTokens.size)

                val promptStart = System.currentTimeMillis()
                val reused = reusePromptPrefix(promptTokens)
                decodeInBatches(promptTokens, reused)
                cachedPromptTokens = promptTokens
                val promptMs = System.currentTimeMillis() - promptStart

                val generationStart = System.currentTimeMillis()
                var generatedTokens = 0
                val raw = StringBuilder()

                for (i in 0 until maxTokens) {
                    val nextToken = LlamaWrapper.nativeChatSample(activeSamplerPtr, ctxPtr)
                    // Negative means sampling failed natively rather than finished; stop
                    // and return what has been generated instead of looping on it.
                    if (nextToken < 0) break
                    if (LlamaWrapper.nativeIsEog(modelPtr, nextToken)) break

                    val piece = LlamaWrapper.nativeTokenToString(modelPtr, nextToken)
                    generatedTokens++
                    raw.append(piece)
                    onToken(piece)

                    if (onDelta != null) {
                        // Re-parse the whole reply and emit only what changed. Costly per
                        // token, but it is the only way partial tool-call syntax becomes
                        // something a client can act on.
                        val deltas = runCatching {
                            nativeJson.decodeFromString<List<NativeChatDelta>>(
                                LlamaWrapper.nativeDiffChatOutput(statePtr, raw.toString(), false)
                            )
                        }.getOrDefault(emptyList())
                        if (deltas.isNotEmpty()) onDelta(deltas)
                    }

                    // Templates can declare stop strings beyond the EOS token; without
                    // honouring them the model runs on past the end of its tool call.
                    if (info.additionalStops.any { it.isNotEmpty() && raw.endsWith(it) }) break

                    if (!LlamaWrapper.nativeDecode(ctxPtr, intArrayOf(nextToken), nPast)) break
                    nPast++
                }

                if (onDelta != null) {
                    val tail = runCatching {
                        nativeJson.decodeFromString<List<NativeChatDelta>>(
                            LlamaWrapper.nativeDiffChatOutput(statePtr, raw.toString(), true)
                        )
                    }.getOrDefault(emptyList())
                    if (tail.isNotEmpty()) onDelta(tail)
                }

                val stats = InferenceStats(
                    promptTokens = promptTokens.size,
                    promptMs = promptMs,
                    generatedTokens = generatedTokens,
                    generationMs = System.currentTimeMillis() - generationStart,
                    reusedPromptTokens = reused,
                )
                _lastStats.value = stats
                LogRepository.log(LogLevel.INFO, stats.summary())
                Log.i(TAG, "chat: ${stats.summary()}")
                val parsed = runCatching {
                    nativeJson.decodeFromString<NativeParsedChat>(
                        LlamaWrapper.nativeParseChatOutput(statePtr, raw.toString(), false)
                    )
                }.getOrElse {
                    // Parsing is best-effort: a model that ignored the tool format still
                    // produced usable prose, and dropping it would be worse than not
                    // recognising a call.
                    NativeParsedChat(content = raw.toString())
                }

                Log.i(TAG, "chat: ${promptTokens.size} prompt tokens, ${raw.length} chars out, ${parsed.toolCalls.size} tool calls")
                ChatGeneration(
                    content = parsed.content.ifEmpty { if (parsed.toolCalls.isEmpty()) raw.toString() else "" },
                    toolCalls = parsed.toolCalls,
                )
            } finally {
                LlamaWrapper.nativeFreeChatParams(statePtr)
                LlamaWrapper.nativeFreeChatSampler(activeSamplerPtr)
            }
        }
    }

    /**
     * Renders a chat request through the model's template and rejects it before any
     * generation starts if the prompt cannot fit the loaded context.
     *
     * A streaming response commits `200 OK` before its first token, so this has to run
     * before the stream opens -- once it is open there is no status left to set, and the
     * client sees a truncated success instead of an error. [generateChat] still checks for
     * itself; this only moves the failure early enough to report it properly.
     */
    suspend fun ensureChatPromptFits(
        messagesJson: String,
        toolsJson: String?,
        toolChoice: String?,
        parallelToolCalls: Boolean,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(modelPtr != 0L && ctxPtr != 0L) { "No model loaded" }
            check(chatTemplatesPtr != 0L) { "Model has no chat template" }

            val statePtr = LlamaWrapper.nativeBuildChatPrompt(
                chatTemplatesPtr,
                messagesJson,
                toolsJson,
                toolChoice,
                parallelToolCalls,
                true,
            )
            if (statePtr == 0L) error(withNativeDetail("Failed to build chat prompt"))
            try {
                val info = nativeJson.decodeFromString<NativeChatPromptInfo>(
                    LlamaWrapper.nativeChatPromptInfo(statePtr)
                )
                requirePromptFits(
                    LlamaWrapper.nativeTokenize(modelPtr, info.prompt, true, parseSpecial = true).size
                )
            } finally {
                LlamaWrapper.nativeFreeChatParams(statePtr)
            }
        }
    }

    /** [ensureChatPromptFits] for the legacy path, which renders its prompt differently. */
    suspend fun ensurePromptFits(messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(modelPtr != 0L && ctxPtr != 0L) { "No model loaded" }
            requirePromptFits(
                LlamaWrapper.nativeTokenize(
                    modelPtr, buildLegacyPrompt(messages), true, parseSpecial = true
                ).size
            )
        }
    }

    /** Renders [messages] with the model's built-in chat template, falling back to ChatML. */
    private fun buildLegacyPrompt(messages: List<ChatMessage>): String {
        val roles = messages.map { it.role }.toTypedArray()
        val contents = messages.map { it.content }.toTypedArray()
        return try {
            LlamaWrapper.nativeApplyChatTemplate(modelPtr, roles, contents, true)
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null } ?: buildString {
            messages.forEach { msg ->
                append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    /**
     * Rejects a prompt that cannot fit the loaded context.
     *
     * llama_decode calls ggml_abort in this case, which kills the process rather than
     * failing the request -- an agentic client with a large system prompt would take the
     * whole server down. One slot is reserved for the token being generated.
     */
    private fun requirePromptFits(promptTokens: Int) {
        val contextSize = LlamaWrapper.nativeNCtx(ctxPtr)
        if (contextSize > 0 && promptTokens >= contextSize) {
            throw PromptTooLongException(promptTokens, contextSize)
        }
    }

    /**
     * Decodes the prompt suffix in batches.
     *
     * A single oversized batch is the other way to reach the same abort, so the work is
     * split into chunks that any sane n_batch accommodates.
     */
    private fun decodeInBatches(promptTokens: IntArray, from: Int) {
        var offset = from
        while (offset < promptTokens.size) {
            val end = minOf(offset + DECODE_BATCH_TOKENS, promptTokens.size)
            val chunk = promptTokens.copyOfRange(offset, end)
            check(LlamaWrapper.nativeDecode(ctxPtr, chunk, nPast)) { "Failed to decode prompt" }
            nPast += chunk.size
            offset = end
        }
    }

    /**
     * Trims the KV cache to the longest prefix shared with [promptTokens] and returns how
     * many tokens were kept.
     *
     * Generation leaves its own sampled tokens in the cache after the prompt, so the reuse
     * point can never exceed what was cached last time. One token is always left to decode:
     * llama_decode needs at least one, and a request identical to the previous one still has
     * to produce logits for the next position.
     */
    private fun reusePromptPrefix(promptTokens: IntArray): Int {
        val limit = minOf(cachedPromptTokens.size, promptTokens.size, nPast)
        var common = 0
        while (common < limit && cachedPromptTokens[common] == promptTokens[common]) common++
        if (common >= promptTokens.size) common = promptTokens.size - 1
        if (common < 0) common = 0

        if (common == 0 || !LlamaWrapper.nativeKvTrim(ctxPtr, common)) {
            LlamaWrapper.nativeKvCacheClear(ctxPtr)
            nPast = 0
            return 0
        }
        nPast = common
        return common
    }

    /**
     * Embeds each input with the currently loaded model.
     *
     * Takes the same lock as generation: embedding builds its own context but still reads
     * the model, which must not be freed underneath it.
     */
    suspend fun embed(inputs: List<String>, nThreads: Int): List<FloatArray> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(modelPtr != 0L) { "No model loaded" }
                inputs.map { text ->
                    LlamaWrapper.nativeEmbed(modelPtr, text, nThreads)
                        ?: error(withNativeDetail("Failed to embed input"))
                }
            }
        }

    suspend fun unloadModel() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                releaseNative()
                _loadedModel.value = null
                LogRepository.log(LogLevel.INFO, "Model unloaded")
            }
        }
    }

    private fun releaseNative() {
        cachedPromptTokens = IntArray(0)
        if (chatTemplatesPtr != 0L) { LlamaWrapper.nativeFreeChatTemplates(chatTemplatesPtr); chatTemplatesPtr = 0L }
        if (ctxPtr != 0L) { LlamaWrapper.nativeFreeContext(ctxPtr); ctxPtr = 0L }
        if (samplerPtr != 0L) { LlamaWrapper.nativeFreeSampler(samplerPtr); samplerPtr = 0L }
        if (modelPtr != 0L) { LlamaWrapper.nativeFreeModel(modelPtr); modelPtr = 0L }
        nPast = 0
    }

    private fun withNativeDetail(message: String): String {
        val detail = try {
            LlamaWrapper.nativeGetLastError()?.trim().takeIf { !it.isNullOrEmpty() }
        } catch (_: Throwable) {
            null
        }
        return if (detail != null) "$message; native: $detail" else message
    }
}
