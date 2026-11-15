package com.jimgrok.petllama.network

/** A readable chain of exception class names and messages, including when message is null. */
fun Throwable.explain(): String {
    val parts = ArrayList<String>(3)
    var current: Throwable? = this
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current) && parts.size < 4) {
        val message = current.message?.takeIf { it.isNotBlank() } ?: "(no message)"
        parts += "${current.javaClass.simpleName}: $message"
        current = current.cause
    }
    return parts.joinToString(" | ")
}

/** Chat-banner text. Connection drops name the cause and say the earlier bubbles were kept. */
fun Throwable.toUserMessage(): String {
    val detail = explain()
    val lower = detail.lowercase()
    return when {
        "connection abort" in lower || "econnaborted" in lower || "connection reset" in lower ->
            "The connection dropped while the model was still working. Messages and tool results above were kept. ($detail)"
        else -> detail
    }
}

internal fun isTransientNetworkError(error: Throwable): Boolean {
    val text = buildString {
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            append(current.javaClass.name)
            append(' ')
            append(current.message.orEmpty())
            append(' ')
            current = current.cause
        }
    }.lowercase()
    return listOf(
        "connection abort",
        "econnaborted",
        "connection reset",
        "econnreset",
        "broken pipe",
        "unexpected end",
        "timeout",
        "eoferror",
        "eofexception",
        "stream was reset",
        "failed to connect"
    ).any { it in text }
}
