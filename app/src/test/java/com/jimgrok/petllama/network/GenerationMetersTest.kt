package com.jimgrok.petllama.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationMetersTest {

    @Test
    fun timingsPreferCacheSizeAndReportBothSpeeds() {
        val stats = parseCompletionStats(
            """
            {"choices":[{"finish_reason":"stop","delta":{}}],
             "timings":{"cache_n":12300,"prompt_n":8000,"predicted_n":200,
                        "predicted_per_second":48.2,"prompt_per_second":310.5}}
            """.trimIndent()
        )!!
        assertEquals(48.2, stats.predictedPerSecond!!, 0.01)
        assertEquals(310.5, stats.promptPerSecond!!, 0.01)
        assertEquals(12300, stats.usedTokens)
    }

    @Test
    fun timingsSumPromptAndPredictedWhenCacheIsMissing() {
        val stats = parseCompletionStats(
            """{"timings":{"prompt_n":100,"predicted_n":25,"predicted_per_second":10}}"""
        )!!
        assertEquals(125, stats.usedTokens)
        assertEquals(10.0, stats.predictedPerSecond!!, 0.01)
        assertNull(stats.promptPerSecond)
    }

    @Test
    fun usageTokensAreUsedWhenTimingsHaveNoCounts() {
        val stats = parseCompletionStats(
            """{"usage":{"prompt_tokens":40,"completion_tokens":10,"total_tokens":50}}"""
        )!!
        assertEquals(50, stats.usedTokens)
        assertNull(stats.predictedPerSecond)
    }

    @Test
    fun propsAndSlotsProvideContextLength() {
        assertEquals(32768, parseContextSize("""{"default_generation_settings":{"n_ctx":32768}}"""))
        assertEquals(4096, parseContextSize("""{"n_ctx":4096}"""))
        assertEquals(
            8192,
            parseContextSize("""{"default_generation_settings":{"params":{"n_ctx":8192}}}""")
        )
        assertEquals(16384, parseContextSize("""[{"id":0,"n_ctx":4096},{"id":1,"n_ctx":16384}]"""))
        assertNull(parseContextSize("not json"))
        assertNull(parseContextSize("""{"default_generation_settings":{}}"""))
    }

    @Test
    fun contextBandBreaksAt70And90Percent() {
        assertEquals(ContextBand.GREEN, contextFill(699, 1000)!!.band)
        assertEquals(ContextBand.YELLOW, contextFill(700, 1000)!!.band)
        assertEquals(ContextBand.YELLOW, contextFill(900, 1000)!!.band)
        assertEquals(ContextBand.RED, contextFill(901, 1000)!!.band)
        assertEquals(0.5f, contextFill(16, 32)!!.fraction, 0.001f)
        assertNull(contextFill(10, 0))
        assertNull(contextFill(null, 32))
    }

    @Test
    fun tokenCountsUseAShortThousandsForm() {
        assertEquals("999", formatTokenCount(999))
        assertEquals("12.3k", formatTokenCount(12300))
        assertEquals("32k", formatTokenCount(32000))
        assertEquals("32.8k", formatTokenCount(32768))
    }

    @Test
    fun liveSpeedDividesContentDeltasByTimeSinceTheFirst() {
        val speed = LiveTokenSpeed()
        assertNull(speed.onContentDelta(0L))
        val value = speed.onContentDelta(2_000_000_000L)
        assertEquals(1.0, value!!, 0.01)
    }
}
