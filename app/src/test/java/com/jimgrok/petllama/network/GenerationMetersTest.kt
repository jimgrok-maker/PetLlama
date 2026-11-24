package com.jimgrok.petllama.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationMetersTest {

    @Test
    fun timingsAddCachePromptAndPredicted() {
        val stats = parseCompletionStats(
            """
            {"choices":[{"finish_reason":"stop","delta":{}}],
             "timings":{"cache_n":12300,"prompt_n":8000,"predicted_n":200,
                        "predicted_per_second":48.2,"prompt_per_second":310.5}}
            """.trimIndent()
        )!!
        assertEquals(48.2, stats.predictedPerSecond!!, 0.01)
        assertEquals(310.5, stats.promptPerSecond!!, 0.01)
        assertEquals(20500, stats.usedTokens)
    }

    @Test
    fun zeroCacheDoesNotHideTheRestOfTheContext() {
        val stats = parseCompletionStats(
            """{"timings":{"cache_n":0,"prompt_n":1400,"predicted_n":80,"predicted_per_second":59.8,"prompt_per_second":689}}"""
        )!!
        assertEquals(1480, stats.usedTokens)
        assertEquals(59.8, stats.predictedPerSecond!!, 0.01)
        val placeholder = parseCompletionStats(
            """{"timings":{"cache_n":0,"predicted_per_second":59.8,"prompt_per_second":689}}"""
        )!!
        assertNull(placeholder.usedTokens)
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
    fun v1ModelsReportContextLengthAndPreferTheSelectedModel() {
        val body = """
            {"object":"list","data":[
              {"id":"small","object":"model","max_model_len":8192},
              {"id":"qwen3.8-27b","object":"model","max_model_len":32768,
               "meta":{"n_ctx_train":131072}},
              {"id":"alias","context_length":4096},
              {"id":"window","max_context_length":2048},
              {"id":"ctx","context_window":1024},
              {"id":"meta","meta":{"n_ctx":16384}}
            ]}
        """.trimIndent()
        val models = modelsFromJson(body)
        assertEquals(8192, models.first { it.id == "small" }.contextLength)
        assertEquals(32768, pickModelContextLength(models, "qwen3.8-27b"))
        assertEquals(8192, pickModelContextLength(models, "missing-model"))
        assertEquals(8192, pickModelContextLength(models, null))
        assertEquals(4096, models.first { it.id == "alias" }.contextLength)
        assertEquals(2048, models.first { it.id == "window" }.contextLength)
        assertEquals(1024, models.first { it.id == "ctx" }.contextLength)
        assertEquals(16384, models.first { it.id == "meta" }.contextLength)
        val one = modelsFromJson("""{"id":"ninfer","max_model_len":65536}""")
        assertEquals(65536, pickModelContextLength(one, "ninfer"))
        assertEquals(16384, modelContextLength(
            com.google.gson.JsonParser.parseString("""{"meta":{"n_ctx_train":16384}}""").asJsonObject
        ))
    }

    @Test
    fun manualContextSizeOverridesDetection() {
        assertEquals(8192, effectiveContextLength(8192, 32768))
        assertEquals(32768, effectiveContextLength(null, 32768))
        assertEquals(32768, effectiveContextLength(0, 32768))
        assertEquals(32768, effectiveContextLength(-5, 32768))
        assertNull(effectiveContextLength(null, null))
        assertNull(effectiveContextLength(0, 0))
        assertNull(parseContextOverride(""))
        assertNull(parseContextOverride("0"))
        assertNull(parseContextOverride("auto"))
        assertEquals(32768, parseContextOverride(" 32768 "))
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
