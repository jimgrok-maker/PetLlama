package com.jimgrok.petllama.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextTest {

    @Test
    fun closedThinkTagIsSeparatedFromTheAnswer() {
        val split = extractThinkTags("<think>count the words</think>\n\nThere are three.")
        assertEquals("count the words", split.reasoning)
        assertEquals("There are three.", split.answer)
        assertFalse(split.stillThinking)
    }

    @Test
    fun orphanClosingThinkTagSplitsPrefilledReasoning() {
        val split = extractThinkTags("prefilled thought\n</think>\n\nFinal answer.")
        assertEquals("prefilled thought", split.reasoning)
        assertEquals("Final answer.", split.answer)
        assertFalse(split.stillThinking)
    }

    @Test
    fun unclosedThinkTagStaysInProgress() {
        val split = extractThinkTags("Hello <think>still working")
        assertEquals("still working", split.reasoning)
        assertEquals("Hello", split.answer)
        assertTrue(split.stillThinking)
    }

    @Test
    fun severalThinkBlocksAreJoined() {
        val split = extractThinkTags("<think>one</think>Answer<think>two</think>")
        assertEquals("one\n\ntwo", split.reasoning)
        assertEquals("Answer", split.answer)
    }

    @Test
    fun fencedCodeIsSplitFromTheProse() {
        val blocks = splitFencedCode("See:\n```kotlin\nval n = 1\n```\nDone")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Prose)
        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val n = 1", code.code)
        assertEquals("Done", (blocks[2] as MarkdownBlock.Prose).markdown)
    }

    @Test
    fun fourBacktickFenceContainsTripleBacktickExample() {
        val markdown = "````markdown\n```kotlin\nval x = 1\n```\n````"
        val blocks = splitFencedCode(markdown)
        assertEquals(1, blocks.size)
        val code = blocks.single() as MarkdownBlock.Code
        assertEquals("markdown", code.language)
        assertTrue(code.code.contains("```kotlin"))
    }
}
