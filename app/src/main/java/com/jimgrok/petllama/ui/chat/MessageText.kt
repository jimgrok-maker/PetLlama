package com.jimgrok.petllama.ui.chat

/** Answer text separated from model reasoning. */
data class ReasonedText(
    val reasoning: String,
    val answer: String,
    /** True when a think tag is still open, so the UI can say "Thinking…". */
    val stillThinking: Boolean
)

/**
 * Pulls `<think>...</think>` blocks out of assistant text.
 * An unclosed tag at the end counts as in-progress reasoning.
 */
fun extractThinkTags(raw: String): ReasonedText {
    val closed = Regex("(?s)<think>(.*?)</think>")
    val pieces = closed.findAll(raw).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    var rest = closed.replace(raw, "")
    val open = rest.lastIndexOf("<think>")
    var stillThinking = false
    val tail = if (open >= 0) {
        stillThinking = true
        val inner = rest.substring(open + "<think>".length).trim()
        rest = rest.substring(0, open)
        inner
    } else {
        ""
    }
    val reasoning = (pieces + listOfNotNull(tail.takeIf { it.isNotEmpty() })).joinToString("\n\n").trim()
    return ReasonedText(reasoning, rest.trim(), stillThinking)
}

sealed class MarkdownBlock {
    data class Prose(val markdown: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
}

/** Splits fenced ``` code blocks out of markdown so they can scroll and be copied. */
fun splitFencedCode(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    val fence = "```"
    while (index < text.length) {
        val start = text.indexOf(fence, index)
        if (start < 0) {
            blocks += MarkdownBlock.Prose(text.substring(index))
            break
        }
        if (start > index) blocks += MarkdownBlock.Prose(text.substring(index, start))
        val headerEnd = text.indexOf('\n', start + fence.length)
        if (headerEnd < 0) {
            blocks += MarkdownBlock.Prose(text.substring(start))
            break
        }
        val language = text.substring(start + fence.length, headerEnd).trim()
        val close = text.indexOf(fence, headerEnd + 1)
        if (close < 0) {
            blocks += MarkdownBlock.Code(language, text.substring(headerEnd + 1))
            break
        }
        blocks += MarkdownBlock.Code(language, text.substring(headerEnd + 1, close).trimEnd())
        index = close + fence.length
        if (index < text.length && text[index] == '\n') index++
    }
    return blocks.filter { block ->
        when (block) {
            is MarkdownBlock.Code -> true
            is MarkdownBlock.Prose -> block.markdown.isNotBlank()
        }
    }
}
