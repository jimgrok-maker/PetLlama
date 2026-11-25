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
 * Also handles orphan `</think>` when a chat template prefilled `<think>`.
 */
fun extractThinkTags(raw: String): ReasonedText {
    val closed = Regex("""(?is)<think>(.*?)</think>""")
    val pieces = closed.findAll(raw).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    var rest = closed.replace(raw, "")
    val open = rest.lastIndexOf("<think>", ignoreCase = true)
    var stillThinking = false
    val tail = if (open >= 0) {
        stillThinking = true
        val inner = rest.substring(open + "<think>".length).trim()
        rest = rest.substring(0, open)
        inner
    } else {
        ""
    }
    val orphanClose = rest.indexOf("</think>", ignoreCase = true)
    val prefixReasoning = if (orphanClose >= 0) {
        val before = rest.substring(0, orphanClose).trim()
        rest = rest.substring(orphanClose + "</think>".length)
        before
    } else {
        ""
    }
    val reasoning = (listOfNotNull(prefixReasoning.takeIf { it.isNotEmpty() }) + pieces + listOfNotNull(tail.takeIf { it.isNotEmpty() }))
        .joinToString("\n\n")
        .trim()
    return ReasonedText(reasoning, rest.trim(), stillThinking)
}

sealed class MarkdownBlock {
    data class Prose(val markdown: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
}

/** Splits fenced code blocks out of markdown so they can scroll and be copied. */
fun splitFencedCode(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    val proseBuf = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmedStart = line.trimStart()
        val fenceLen = trimmedStart.takeWhile { it == '`' }.length
        if (fenceLen >= 3) {
            if (proseBuf.isNotEmpty()) {
                val prose = proseBuf.toString()
                proseBuf.clear()
                if (prose.isNotBlank()) blocks += MarkdownBlock.Prose(prose)
            }
            val language = trimmedStart.substring(fenceLen).trim()
            val codeLines = mutableListOf<String>()
            var j = i + 1
            var foundClose = false
            while (j < lines.size) {
                val candidate = lines[j].trimStart()
                val closeLen = candidate.takeWhile { it == '`' }.length
                if (closeLen >= fenceLen && candidate.substring(closeLen).isBlank()) {
                    foundClose = true
                    break
                }
                codeLines += lines[j]
                j++
            }
            if (foundClose) {
                blocks += MarkdownBlock.Code(language, codeLines.joinToString("\n").trimEnd())
                i = j + 1
                continue
            } else {
                blocks += MarkdownBlock.Code(language, codeLines.joinToString("\n"))
                break
            }
        }
        if (proseBuf.isNotEmpty()) proseBuf.append('\n')
        proseBuf.append(line)
        i++
    }
    if (proseBuf.isNotEmpty()) {
        val prose = proseBuf.toString()
        if (prose.isNotBlank()) blocks += MarkdownBlock.Prose(prose)
    }
    return blocks
}
