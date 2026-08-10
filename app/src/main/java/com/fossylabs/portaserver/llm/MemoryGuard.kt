package com.fossylabs.portaserver.llm

/**
 * Estimates whether a model can be loaded without exhausting device memory.
 *
 * Peak usage is derived from the GGUF file size rather than a curated per-model figure,
 * so arbitrary HuggingFace downloads are covered and not just a blessed list. The result
 * is an estimate: without parsing GGUF metadata for layer count and embedding width the
 * KV-cache term cannot be exact, so surface it to users as approximate.
 */
object MemoryGuard {

    enum class Verdict {
        /** Comfortably within budget. */
        OK,

        /** Should load, but leaves little headroom for the OS and other apps. */
        TIGHT,

        /** Expected to exhaust memory; loading will likely be killed by the OS. */
        EXCEEDS,
    }

    private const val BYTES_IN_GB = 1024f * 1024f * 1024f

    /** Allocator and mmap slack on top of the raw weights. */
    private const val WEIGHTS_OVERHEAD = 1.10f

    /** App, Compose, Ktor, and graphics working set outside the model. */
    private const val RUNTIME_OVERHEAD_BYTES = 320L * 1024L * 1024L

    /**
     * KV-cache bytes per token, per GB of weights, assuming an fp16 cache.
     *
     * Calibrated against known architectures: a 7B model (~4.4 GB at Q4_K_M) needs about
     * 512 KiB/token, and a 1B model (~0.8 GB) about 130 KiB/token — both land within a
     * few percent of this constant, because layer count and embedding width scale
     * together with parameter count.
     */
    private const val KV_BYTES_PER_TOKEN_PER_GB = 122_000L

    /** Above this fraction of total RAM a load is refused rather than merely flagged. */
    private const val EXCEEDS_FRACTION = 0.85f

    /** Below this fraction a load is considered comfortable. */
    private const val OK_FRACTION = 0.60f

    /** Approximate peak resident bytes for a model of [fileSizeBytes] at [nCtx] context. */
    fun estimatePeakBytes(fileSizeBytes: Long, nCtx: Int): Long {
        if (fileSizeBytes <= 0L) return 0L
        val weightsGb = fileSizeBytes / BYTES_IN_GB
        val kvBytes = (nCtx.toLong() * KV_BYTES_PER_TOKEN_PER_GB * weightsGb).toLong()
        return (fileSizeBytes * WEIGHTS_OVERHEAD).toLong() + kvBytes + RUNTIME_OVERHEAD_BYTES
    }

    /**
     * Grades [estimatedPeakBytes] against the device's total RAM. Returns [Verdict.OK]
     * when the size is unknown — an unverifiable model should not be blocked.
     */
    fun check(specs: DeviceSpecs, estimatedPeakBytes: Long): Verdict {
        if (estimatedPeakBytes <= 0L || specs.totalRamBytes <= 0L) return Verdict.OK
        val fraction = estimatedPeakBytes.toFloat() / specs.totalRamBytes.toFloat()
        return when {
            fraction >= EXCEEDS_FRACTION -> Verdict.EXCEEDS
            fraction >= OK_FRACTION -> Verdict.TIGHT
            else -> Verdict.OK
        }
    }

    /** Convenience wrapper over [estimatePeakBytes] + [check]. */
    fun evaluate(specs: DeviceSpecs, fileSizeBytes: Long?, nCtx: Int): Verdict {
        if (fileSizeBytes == null) return Verdict.OK
        return check(specs, estimatePeakBytes(fileSizeBytes, nCtx))
    }
}
