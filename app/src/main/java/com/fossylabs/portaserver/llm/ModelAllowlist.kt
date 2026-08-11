package com.fossylabs.portaserver.llm

import kotlinx.serialization.Serializable

/**
 * A vetted model, as shipped in `assets/model_allowlist.json`.
 *
 * Live HuggingFace search sorts by download count and cannot reliably be narrowed to
 * text generation, so it surfaces embedding and speech models alongside chat models, and
 * parameter counts have to be guessed from filenames. A curated list gives a trustworthy
 * first run and lets new files be blessed without shipping an APK.
 */
@Serializable
data class AllowlistEntry(
    /** Display name. */
    val name: String,
    /** HuggingFace repository, e.g. `bartowski/Qwen2.5-7B-Instruct-GGUF`. */
    val modelId: String,
    /** Exact GGUF file within the repository. */
    val fileName: String,
    val sizeBytes: Long,
    /** Minimum device RAM in GB at which [MemoryGuard] will not report EXCEEDS. */
    val minRamGb: Int,
    val quant: String,
    val description: String,
)

@Serializable
data class ModelAllowlist(
    val version: Int = 1,
    val models: List<AllowlistEntry> = emptyList(),
)

/**
 * Projects an entry into the shared [ModelInfo] shape so curated models reuse the
 * existing detail sheet and download flow unchanged. [ModelInfo.name] must be the
 * repository id, since that is what the file lookup keys on.
 */
fun AllowlistEntry.toModelInfo(): ModelInfo = ModelInfo(
    path = "https://huggingface.co/$modelId",
    name = modelId,
    isLocal = false,
    sizeBytes = sizeBytes,
)
