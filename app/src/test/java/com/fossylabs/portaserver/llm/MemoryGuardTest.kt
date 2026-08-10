package com.fossylabs.portaserver.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the guard's verdicts against a nominal 8 GB phone — the reference device this was
 * tuned on. These assertions are the reason the thresholds are what they are; changing a
 * constant in [MemoryGuard] should either keep these passing or be a deliberate retune.
 */
class MemoryGuardTest {

    private fun gb(value: Double): Long = (value * 1024 * 1024 * 1024).toLong()

    private val eightGbDevice = DeviceSpecs(
        totalRamBytes = gb(8.0),
        availableRamBytes = gb(3.0),
        cpuCores = 8,
        socModel = "MT6785",
        hasVulkan = true,
    )

    private fun verdictFor(fileSizeGb: Double) =
        MemoryGuard.evaluate(eightGbDevice, gb(fileSizeGb), nCtx = 2048)

    @Test
    fun `small model is comfortable on 8gb`() {
        // ~1B at Q4_K_M.
        assertEquals(MemoryGuard.Verdict.OK, verdictFor(0.8))
    }

    @Test
    fun `7b q4 is tight but permitted on 8gb`() {
        assertEquals(MemoryGuard.Verdict.TIGHT, verdictFor(4.37))
    }

    @Test
    fun `7b q5 is tight but permitted on 8gb`() {
        assertEquals(MemoryGuard.Verdict.TIGHT, verdictFor(4.65))
    }

    @Test
    fun `13b q4 exceeds 8gb`() {
        assertEquals(MemoryGuard.Verdict.EXCEEDS, verdictFor(7.87))
    }

    @Test
    fun `unknown file size is never blocked`() {
        assertEquals(
            MemoryGuard.Verdict.OK,
            MemoryGuard.evaluate(eightGbDevice, fileSizeBytes = null, nCtx = 2048),
        )
    }

    @Test
    fun `larger context raises the estimate`() {
        val at2k = MemoryGuard.estimatePeakBytes(gb(4.37), nCtx = 2048)
        val at8k = MemoryGuard.estimatePeakBytes(gb(4.37), nCtx = 8192)
        assert(at8k > at2k) { "KV cache must grow with context: $at2k vs $at8k" }
    }

    @Test
    fun `recommender does not promote an 8gb device past 7b`() {
        // advertisedMem reports a nominal 8.0 GB here, which previously crossed the
        // old `< 8f` boundary and landed on a 13B tier the guard then rejected.
        val tier = ModelRecommender.recommend(eightGbDevice)
        assertEquals(7f, tier.maxParamBillion)
    }
}
