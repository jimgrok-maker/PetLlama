package com.jimgrok.petllama.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One row in the Reasoning dropdown.
 * [reasoningEffort] and [enableThinking] are omitted from the request when null,
 * so Default adds no fields.
 */
data class ReasoningChoice(
    val id: String,
    val label: String,
    val reasoningEffort: String? = null,
    val enableThinking: Boolean? = null,
    /** Also put reasoning_effort inside chat_template_kwargs for templates that read it there. */
    val effortInKwargs: Boolean = false
) {
    companion object {
        val Default = ReasoningChoice(id = "default", label = "Default")
        val fallback: List<ReasoningChoice> = listOf(
            Default,
            ReasoningChoice(id = "off", label = "Off", enableThinking = false),
            effort("low", "Low"),
            effort("medium", "Medium"),
            effort("high", "High")
        )

        private fun effort(id: String, label: String) = ReasoningChoice(
            id = id,
            label = label,
            reasoningEffort = id,
            enableThinking = true,
            effortInKwargs = true
        )
    }
}

/** Fields added to a chat request for the chosen reasoning level. Both null means Default. */
data class ReasoningRequest(
    val reasoningEffort: String? = null,
    val chatTemplateKwargs: JsonObject? = null
)

fun ChatRequest.withReasoning(choice: ReasoningChoice): ChatRequest {
    val fields = reasoningRequestFor(choice)
    return copy(
        reasoningEffort = fields.reasoningEffort,
        chatTemplateKwargs = fields.chatTemplateKwargs
    )
}

fun reasoningRequestFor(choice: ReasoningChoice): ReasoningRequest {
    if (choice.id == ReasoningChoice.Default.id &&
        choice.reasoningEffort == null &&
        choice.enableThinking == null
    ) {
        return ReasoningRequest()
    }
    val kwargs = JsonObject()
    if (choice.enableThinking != null) {
        kwargs.addProperty("enable_thinking", choice.enableThinking)
    }
    if (choice.effortInKwargs && !choice.reasoningEffort.isNullOrBlank()) {
        kwargs.addProperty("reasoning_effort", choice.reasoningEffort)
    }
    return ReasoningRequest(
        reasoningEffort = choice.reasoningEffort,
        chatTemplateKwargs = kwargs.takeIf { kwargs.size() > 0 }
    )
}

/**
 * Options for the model behind this /props document.
 * Uses chat_template_caps.reasoning_effort_levels when the server probed them,
 * otherwise the level names written in the chat template. Off is included only
 * when the template (or kwargs) mentions enable_thinking.
 * Nothing detected, including a missing /props body, falls back to
 * Default / Off / Low / Medium / High.
 */
fun reasoningChoicesFromProps(propsJson: String?): List<ReasoningChoice> {
    if (propsJson.isNullOrBlank()) return ReasoningChoice.fallback
    val root = runCatching { JsonParser.parseString(propsJson) }.getOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject
        ?: return ReasoningChoice.fallback
    val template = chatTemplateText(root)
    val caps = root.get("chat_template_caps")?.takeIf { it.isJsonObject }?.asJsonObject
    val levels = caps?.get("reasoning_effort_levels")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { element ->
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                element.asString.trim().lowercase().ifEmpty { null }
            } else {
                null
            }
        }
        .orEmpty()
    val mentionsThinking = template.contains("enable_thinking") ||
        root.get("chat_template_kwargs")?.toString()?.contains("enable_thinking") == true
    val mentionsEffort = template.contains("reasoning_effort") || levels.isNotEmpty()
    if (!mentionsThinking && !mentionsEffort) return ReasoningChoice.fallback
    val names = if (levels.isNotEmpty()) levels else effortNamesInTemplate(template)
    return buildChoices(includeOff = mentionsThinking, levelNames = names)
}

/** Qwen-style jinja, or any template string, without the rest of /props. */
fun reasoningChoicesFromTemplate(template: String?): List<ReasoningChoice> {
    if (template.isNullOrBlank()) return ReasoningChoice.fallback
    val mentionsThinking = template.contains("enable_thinking")
    val mentionsEffort = template.contains("reasoning_effort")
    if (!mentionsThinking && !mentionsEffort) return ReasoningChoice.fallback
    return buildChoices(
        includeOff = mentionsThinking,
        levelNames = if (mentionsEffort) effortNamesInTemplate(template) else emptyList()
    )
}

private fun chatTemplateText(root: JsonObject): String {
    val value = root.get("chat_template") ?: return ""
    return when {
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
        value.isJsonObject -> value.asJsonObject.get("template")
            ?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        else -> ""
    }
}

private fun effortNamesInTemplate(template: String): List<String> {
    val found = linkedSetOf<String>()
    // Levels are usually compared to a local copy of reasoning_effort, not the
    // kwarg itself: `_effort_raw == "high"`. Keep known names that appear quoted.
    for (match in quoted.findAll(template)) {
        val name = match.groupValues[1].lowercase()
        if (name in KNOWN_LEVELS) found += name
    }
    for (match in inList.findAll(template)) {
        for (name in quoted.findAll(match.groupValues[1])) {
            val id = name.groupValues[1].lowercase()
            if (id !in OFF_ALIASES) found += id
        }
    }
    return found.toList()
}

private fun buildChoices(includeOff: Boolean, levelNames: List<String>): List<ReasoningChoice> {
    val options = mutableListOf(ReasoningChoice.Default)
    if (includeOff) {
        // "none" is only sent when the template itself lists it. Otherwise
        // llama-server can reject the field, so Off is enable_thinking=false alone.
        val acceptsNone = levelNames.any { it == "none" } || false
        options += ReasoningChoice(
            id = "off",
            label = "Off",
            reasoningEffort = if (acceptsNone) "none" else null,
            enableThinking = false,
            effortInKwargs = acceptsNone
        )
    }
    val ordered = levelNames
        .filter { it.isNotBlank() && it !in OFF_ALIASES }
        .distinct()
        .sortedBy { rank(it) }
    for (name in ordered) {
        options += ReasoningChoice(
            id = name,
            label = labelFor(name),
            reasoningEffort = name,
            enableThinking = if (includeOff) true else null,
            effortInKwargs = true
        )
    }
    if (options.size == 1) return ReasoningChoice.fallback
    return options
}

private fun rank(name: String): Int {
    val known = KNOWN_ORDER.indexOf(name)
    return if (known >= 0) known else KNOWN_ORDER.size
}

private fun labelFor(name: String): String = when (name) {
    "minimal" -> "Minimal"
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "extra_high", "xhigh" -> "Extra high"
    "max" -> "Max"
    else -> name.replace('_', ' ').replaceFirstChar { it.titlecase() }
}

private val OFF_ALIASES = setOf("none", "off")
private val KNOWN_ORDER = listOf(
    "minimal", "low", "medium", "high", "extra_high", "xhigh", "max"
)
private val KNOWN_LEVELS = KNOWN_ORDER.toSet()
private val inList = Regex("""\bin\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
private val quoted = Regex("""['"]([A-Za-z][A-Za-z0-9_]*)['"]""")
