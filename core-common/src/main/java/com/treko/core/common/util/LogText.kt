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

    fun redact(text: String): String =
        SECRET_PATTERNS.fold(text) { acc, pattern -> pattern.replace(acc) { m -> m.groupValues[1] + "***" } }
}
