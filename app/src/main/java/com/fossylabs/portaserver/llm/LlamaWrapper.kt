package com.fossylabs.portaserver.llm

object LlamaWrapper {

    init {
        System.loadLibrary("llama_bridge")
    }

    // Model
    external fun nativeLoadModel(path: String, nCtx: Int, nGpuLayers: Int): Long
    external fun nativeFreeModel(modelPtr: Long)

    // Context
    external fun nativeNewContext(modelPtr: Long, nCtx: Int, nThreads: Int): Long
    external fun nativeFreeContext(ctxPtr: Long)

    // Tokenization
    external fun nativeTokenize(modelPtr: Long, text: String, addBos: Boolean): IntArray

    // Decoding
    external fun nativeDecode(ctxPtr: Long, tokens: IntArray, nPast: Int): Boolean

    // Sampling
    external fun nativeNewSampler(temperature: Float, topP: Float, seed: Int): Long
    external fun nativeFreeSampler(samplerPtr: Long)
    external fun nativeSample(samplerPtr: Long, ctxPtr: Long): Int

    // Token utilities
    external fun nativeTokenToString(modelPtr: Long, token: Int): String
    external fun nativeEosToken(modelPtr: Long): Int
    external fun nativeNCtx(ctxPtr: Long): Int

    // KV cache
    external fun nativeKvCacheClear(ctxPtr: Long)

    // Chat template
    external fun nativeApplyChatTemplate(
        modelPtr: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistantTurn: Boolean,
    ): String
}
