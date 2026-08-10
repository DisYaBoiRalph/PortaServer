package com.fossylabs.portaserver.llm

object ModelRecommender {

    /**
     * Thresholds are written against advertised RAM (see [DeviceSpecsReader]), which reads
     * about 0.5 GB higher than the older totalMem figure. The boundaries below are set so
     * a nominal 8 GB device still lands on 7B rather than being promoted to 13B — a tier
     * that device cannot honour and that [MemoryGuard] would immediately warn against.
     */
    fun recommend(specs: DeviceSpecs): ModelTier = when {
        specs.totalRamGb < 4f   -> ModelTier(3f,  "Q4_K_M", "Up to 3B params (Q4_K_M)")
        specs.totalRamGb < 6f   -> ModelTier(3f,  "Q5_K_M", "Up to 3B params (Q5_K_M)")
        specs.totalRamGb < 8.5f -> ModelTier(7f,  "Q4_K_M", "Up to 7B params (Q4_K_M)")
        specs.totalRamGb < 12f  -> ModelTier(7f,  "Q5_K_M", "Up to 7B params (Q5_K_M)")
        specs.totalRamGb < 16f  -> ModelTier(13f, "Q4_K_M", "Up to 13B params (Q4_K_M)")
        else                    -> ModelTier(13f, "Q5_K_M", "13B+ or 7B high-quality quant")
    }

    /** Returns true if a model's filename plausibly fits within the tier. */
    fun fitsInTier(modelName: String, tier: ModelTier): Boolean {
        val lower = modelName.lowercase()
        val paramMatch = Regex("""(\d+(?:\.\d+)?)\s*b""").find(lower)
        val params = paramMatch?.groupValues?.get(1)?.toFloatOrNull() ?: return true
        return params <= tier.maxParamBillion
    }

    /**
     * Converts a HuggingFace download count into a 1–5 star popularity rating.
     *
     * Thresholds reflect real-world community trust:
     *   ★★★★★  1 M+   — flagship / widely-adopted (e.g. top Llama/Mistral quants)
     *   ★★★★☆  100 k+ — well-established, broadly recommended
     *   ★★★☆☆  10 k+  — solid community traction
     *   ★★☆☆☆  1 k+   — gaining adoption
     *   ★☆☆☆☆  < 1 k  — new or niche
     */
    fun popularityStars(downloads: Int): Int = when {
        downloads >= 1_000_000 -> 5
        downloads >= 100_000   -> 4
        downloads >= 10_000    -> 3
        downloads >= 1_000     -> 2
        else                   -> 1
    }
}
