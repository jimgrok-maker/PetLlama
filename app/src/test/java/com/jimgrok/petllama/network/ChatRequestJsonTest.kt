package com.jimgrok.petllama.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestJsonTest {

    @Test
    fun nullMaxTokensIsOmittedFromTheRequest() {
        val json = GsonHolder.gson.toJson(
            ChatRequest(model = "qwen", messages = listOf(Msg("user", "hi")), maxTokens = null)
        )
        assertFalse(json, json.contains("max_tokens"))
    }

    @Test
    fun setMaxTokensIsWrittenIntoTheRequest() {
        val json = GsonHolder.gson.toJson(
            ChatRequest(model = "qwen", messages = listOf(Msg("user", "hi")), maxTokens = 4096)
        )
        assertTrue(json, json.contains("\"max_tokens\":4096"))
    }
}
