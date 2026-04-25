package com.fossylabs.portaserver.llm

import com.fossylabs.portaserver.server.LogLevel
import com.fossylabs.portaserver.server.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object LlmInferenceEngine {

    private val mutex = Mutex()

    private var modelPtr: Long = 0L
    private var ctxPtr: Long = 0L
    private var samplerPtr: Long = 0L
    private var nPast: Int = 0

    private val _loadedModel = MutableStateFlow<ModelInfo?>(null)
    val loadedModel: StateFlow<ModelInfo?> = _loadedModel.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
        onToken: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(modelPtr != 0L && ctxPtr != 0L) { "No model loaded" }

            LogRepository.log(LogLevel.INFO, "Generation started (${messages.size} messages, max $maxTokens tokens)")

            // Build prompt using the model's built-in chat template (falls back to ChatML)
            val roles = messages.map { it.role }.toTypedArray()
            val contents = messages.map { it.content }.toTypedArray()
            val prompt = try {
                LlamaWrapper.nativeApplyChatTemplate(modelPtr, roles, contents, true)
                    .takeIf { it.isNotEmpty() }
            } catch (_: Exception) { null } ?: buildString {
                messages.forEach { msg ->
                    append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
                }
                append("<|im_start|>assistant\n")
            }

            LlamaWrapper.nativeKvCacheClear(ctxPtr)
            nPast = 0

            val promptTokens = LlamaWrapper.nativeTokenize(modelPtr, prompt, true)
            val decodeOk = LlamaWrapper.nativeDecode(ctxPtr, promptTokens, nPast)
            check(decodeOk) { "Failed to decode prompt" }
            nPast += promptTokens.size

            val eosToken = LlamaWrapper.nativeEosToken(modelPtr)
            repeat(maxTokens) {
                val nextToken = LlamaWrapper.nativeSample(samplerPtr, ctxPtr)
                if (nextToken == eosToken) return@withContext

                val piece = LlamaWrapper.nativeTokenToString(modelPtr, nextToken)
                onToken(piece)

                val ok = LlamaWrapper.nativeDecode(ctxPtr, intArrayOf(nextToken), nPast)
                nPast++
                if (!ok) return@withContext
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
