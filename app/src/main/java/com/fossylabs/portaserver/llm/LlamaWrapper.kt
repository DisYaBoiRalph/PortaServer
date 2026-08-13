package com.fossylabs.portaserver.llm

object LlamaWrapper {

    init {
        System.loadLibrary("llama_bridge")
    }

    // Model
    external fun nativeLoadModel(path: String, nCtx: Int, nGpuLayers: Int): Long
    external fun nativeLoadModelFromFd(fd: Int, nCtx: Int, nGpuLayers: Int): Long
    external fun nativeGetLastError(): String?
    external fun nativeFreeModel(modelPtr: Long)

    // Context
    external fun nativeNewContext(modelPtr: Long, nCtx: Int, nThreads: Int): Long
    external fun nativeFreeContext(ctxPtr: Long)

    // Tokenization
    external fun nativeTokenize(modelPtr: Long, text: String, addBos: Boolean, parseSpecial: Boolean): IntArray

    // Decoding
    external fun nativeDecode(ctxPtr: Long, tokens: IntArray, nPast: Int): Boolean

    // Sampling
    external fun nativeNewSampler(temperature: Float, topP: Float, seed: Int): Long
    external fun nativeFreeSampler(samplerPtr: Long)
    external fun nativeSample(samplerPtr: Long, ctxPtr: Long): Int

    // Token utilities
    external fun nativeTokenToString(modelPtr: Long, token: Int): String
    external fun nativeEosToken(modelPtr: Long): Int

    /** True when the token ends generation; covers every EOG token, not just vocab EOS. */
    external fun nativeIsEog(modelPtr: Long, token: Int): Boolean
    external fun nativeNCtx(ctxPtr: Long): Int

    /** Mean-pooled embedding for [text], or null if it could not be produced. */
    external fun nativeEmbed(modelPtr: Long, text: String, nThreads: Int): FloatArray?

    // KV cache
    external fun nativeKvCacheClear(ctxPtr: Long)

    /** Drops cached KV from [nKeep] onward, keeping the prefix. False if it could not. */
    external fun nativeKvTrim(ctxPtr: Long, nKeep: Int): Boolean

    // Chat template
    external fun nativeApplyChatTemplate(
        modelPtr: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistantTurn: Boolean,
    ): String

    // Tool calling. The OpenAI request crosses this boundary as JSON so llama.cpp can
    // render tools through the model's own template and parse calls back out; mirroring
    // its per-model formats in Kotlin would duplicate what common/chat.h already does.
    external fun nativeInitChatTemplates(modelPtr: Long): Long
    external fun nativeFreeChatTemplates(tmplsPtr: Long)
    external fun nativeChatTemplateSource(tmplsPtr: Long): String

    external fun nativeBuildChatPrompt(
        tmplsPtr: Long,
        messagesJson: String,
        toolsJson: String?,
        toolChoice: String?,
        parallelToolCalls: Boolean,
        addGenerationPrompt: Boolean,
    ): Long

    /** JSON: prompt, format, grammar, grammarLazy, grammarTriggers, preservedTokens, additionalStops. */
    external fun nativeChatPromptInfo(statePtr: Long): String

    /** JSON: content, reasoningContent, toolCalls[{id, name, arguments}]. */
    external fun nativeParseChatOutput(statePtr: Long, text: String, isPartial: Boolean): String

    /**
     * Sampler for a chat request, constrained by the grammar the template produced so a
     * malformed tool call cannot be emitted. Plain sampling when there is no grammar.
     */
    external fun nativeNewChatSampler(
        modelPtr: Long,
        statePtr: Long,
        temperature: Float,
        topP: Float,
        seed: Int,
    ): Long

    /** Samples and accepts one token. Returns a negative value if sampling failed. */
    external fun nativeChatSample(samplerPtr: Long, ctxPtr: Long): Int

    external fun nativeFreeChatSampler(samplerPtr: Long)

    /**
     * Deltas since the previous call, as JSON:
     * [{content, toolCallIndex?, toolCallId?, toolCallName?, toolCallArgs?}].
     */
    external fun nativeDiffChatOutput(statePtr: Long, text: String, isFinal: Boolean): String

    external fun nativeFreeChatParams(statePtr: Long)
}
