package com.jimgrok.petllama.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningOptionsTest {

    private val qwenTemplate = """
        {%- set enable_thinking = enable_thinking if enable_thinking is defined else true %}
        {%- set _effort_raw = (reasoning_effort | string | lower) if reasoning_effort is defined else 'medium' %}
        {%- if _effort_raw == "low" %}brief{%- elif _effort_raw == "medium" %}normal
        {%- elif _effort_raw == "high" %}deeper{%- elif _effort_raw == "extra_high" %}deepest{%- endif %}
    """.trimIndent()

    @Test
    fun qwenTemplateOffersOffAndTheLevelsItNames() {
        val choices = reasoningChoicesFromTemplate(qwenTemplate)
        assertEquals(
            listOf("default", "off", "low", "medium", "high", "extra_high"),
            choices.map { it.id }
        )
        assertEquals(listOf("Default", "Off", "Low", "Medium", "High", "Extra high"), choices.map { it.label })
        val off = choices.first { it.id == "off" }
        assertEquals(false, off.enableThinking)
        assertNull(off.reasoningEffort)
        val high = choices.first { it.id == "high" }
        assertEquals("high", high.reasoningEffort)
        assertEquals(true, high.enableThinking)
    }

    @Test
    fun propsCapsOverrideTheLevelList() {
        val props = """{"chat_template":${com.google.gson.Gson().toJson(qwenTemplate)},"chat_template_caps":{"reasoning_effort_levels":["low","medium","xhigh"]}}"""
        val choices = reasoningChoicesFromProps(props)
        assertEquals(listOf("default", "off", "low", "medium", "xhigh"), choices.map { it.id })
        assertEquals("Extra high", choices.first { it.id == "xhigh" }.label)
    }

    @Test
    fun missingPropsFallsBackToDefaultOffLowMediumHigh() {
        assertEquals(
            listOf("default", "off", "low", "medium", "high"),
            reasoningChoicesFromProps(null).map { it.id }
        )
        assertEquals(
            reasoningChoicesFromProps(null).map { it.id },
            reasoningChoicesFromProps("""{"default_generation_settings":{"n_ctx":32768}}""").map { it.id }
        )
    }

    @Test
    fun defaultAddsNothingAndOtherLevelsSerialize() {
        val base = ChatRequest(
            model = "qwen",
            messages = listOf(Msg("user", "hi")),
            temperature = 0.7f,
            topP = 0.95f,
            maxTokens = 512
        )
        val plain = GsonHolder.gson.toJson(base)
        val choices = reasoningChoicesFromTemplate(qwenTemplate)
        val asDefault = base.withReasoning(choices.first { it.id == "default" })
        assertEquals(plain, GsonHolder.gson.toJson(asDefault))
        assertFalse(plain.contains("reasoning_effort"))
        assertFalse(plain.contains("chat_template_kwargs"))

        val off = GsonHolder.gson.toJson(base.withReasoning(choices.first { it.id == "off" }))
        assertTrue(off.contains("\"enable_thinking\":false"))
        assertFalse(off.contains("reasoning_effort"))

        val low = GsonHolder.gson.toJson(base.withReasoning(choices.first { it.id == "low" }))
        assertTrue(low.contains("\"reasoning_effort\":\"low\""))
        assertTrue(low.contains("\"enable_thinking\":true"))

        val extra = GsonHolder.gson.toJson(base.withReasoning(choices.first { it.id == "extra_high" }))
        assertTrue(extra.contains("\"reasoning_effort\":\"extra_high\""))
        assertTrue(extra.contains("\"enable_thinking\":true"))
    }
}
