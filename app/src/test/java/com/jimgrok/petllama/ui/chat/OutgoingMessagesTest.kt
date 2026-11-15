package com.jimgrok.petllama.ui.chat

import com.jimgrok.petllama.network.Msg
import org.junit.Assert.assertEquals
import org.junit.Test

class OutgoingMessagesTest {

    @Test
    fun lastUserMessageIsIncludedOnce() {
        val history = listOf(
            UiMessage(1, "user", "first"),
            UiMessage(2, "assistant", "reply"),
            UiMessage(3, "user", "second")
        )
        val msgs = outgoingMessages(history, systemPrompt = "be brief")
        assertEquals(
            listOf(
                Msg("system", "be brief"),
                Msg("user", "first"),
                Msg("assistant", "reply"),
                Msg("user", "second")
            ),
            msgs
        )
        assertEquals(1, msgs.count { it.content == "second" })
    }

    @Test
    fun toolBubblesAreNotSentBackToTheModel() {
        val history = listOf(
            UiMessage(1, "user", "search cats"),
            UiMessage(2, "tool", "found 3", toolName = "SearXNG / search", toolArgs = "{\"q\":\"cats\"}"),
            UiMessage(3, "assistant", "Three pages.")
        )
        assertEquals(
            listOf(Msg("user", "search cats"), Msg("assistant", "Three pages.")),
            outgoingMessages(history, systemPrompt = "")
        )
    }

    @Test
    fun streamingPlaceholderIsLeftOut() {
        val history = listOf(
            UiMessage(1, "user", "hi"),
            UiMessage(-1, "assistant", "partial…", isStreaming = true)
        )
        assertEquals(listOf(Msg("user", "hi")), outgoingMessages(history, systemPrompt = "  "))
    }

    @Test
    fun onlyTheMostRecentMessagesAreKept() {
        val history = (1..25).map { UiMessage(it.toLong(), "user", "m$it") }
        val msgs = outgoingMessages(history, systemPrompt = "", limit = 20)
        assertEquals(20, msgs.size)
        assertEquals("m6", msgs.first().content)
        assertEquals("m25", msgs.last().content)
    }
}
