package com.treko.core.common.util

/**
 * Helpers to put free text (tool results, vision verdicts) into a single diagnostic log line.
 * The text is collapsed to one line, cut to a maximum length, and anything that looks like a
 * credential is masked, so a log export never carries a token by accident.
 */
object LogText {

    private val WHITESPACE = Regex("\\s+")

    /** `Bearer abc`, `access_token=abc`, `"token": "abc"`, `api_key: abc`, `?token=abc`. */
    private val SECRET_PATTERNS = listOf(
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"),
        Regex(
            "(?i)((?:access_token|refresh_token|api_key|apikey|password|token|authorization)" +
                "[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"'&,;}]{4,}"
        )
    )

    /** One line, at most [maxChars] characters (an ellipsis marks a cut), secrets masked. */
    fun preview(text: String?, maxChars: Int): String {
        if (text.isNullOrEmpty()) return ""
        val oneLine = redact(text).replace(WHITESPACE, " ").trim()
        if (oneLine.length <= maxChars) return oneLine
        return oneLine.take(maxChars.coerceAtLeast(1)).trimEnd() + "…"
    }

    /** Tools whose results are the user's personal notes: only their size goes to the log. */
    val NO_PREVIEW_TOOLS: Set<String> = setOf("manage_vault_memory")

    /**
     * What the log says about [toolName]'s [result]: a masked [preview] of at most [maxChars],
     * or only its length (or "error") for [NO_PREVIEW_TOOLS]; "<no result>" when empty.
     */
    fun toolResult(toolName: String, result: String?, maxChars: Int): String {
        if (result.isNullOrEmpty()) return "<no result>"
        if (toolName in NO_PREVIEW_TOOLS) {
            return if (result.trimStart().startsWith("error", ignoreCase = true)) "error" else "${result.length} chars"
        }
        return preview(result, maxChars).ifEmpty { "<no result>" }
    }

    fun redact(text: String): String =
        SECRET_PATTERNS.fold(text) { acc, pattern -> pattern.replace(acc) { m -> m.groupValues[1] + "***" } }
}
