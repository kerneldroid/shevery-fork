package moe.shizuku.manager.utils

object CommandSanitizer {
    fun sanitize(raw: String): String {
        var t = raw.trim()
        // Strip reasoning blocks; fall back to truncating an unclosed tag
        // (truncated output never contains the closing tag).
        t = REGEX_THINKING_CLOSED.replace(t, "")
        t = REGEX_THINKING_UNCLOSED.replace(t, "")
        // Extract the first fenced code block wherever it appears; prose
        // around the fence must not leak into the command field.
        REGEX_FENCE_FIND.find(t)?.let { t = it.groupValues[1] } ?: run {
            t = REGEX_FENCE_OPEN.replace(t, "")
            t = REGEX_FENCE_CLOSE.replace(t, "")
        }
        // Single-command policy: first non-empty line only, so multi-line
        // output can never inject extra lines into the command field.
        t = t.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return t
    }

    private val REGEX_THINKING_CLOSED = Regex("(?is)<thinking>.*?</thinking>")
    private val REGEX_THINKING_UNCLOSED = Regex("(?is)<thinking>.*$")
    private val REGEX_FENCE_FIND = Regex("(?is)```[a-zA-Z0-9_-]*\\s*\\n(.*?)\\n```")
    private val REGEX_FENCE_OPEN = Regex("(?is)^\\s*```[a-zA-Z0-9_-]*\\s*(\\n|$)")
    private val REGEX_FENCE_CLOSE = Regex("\\s*```\\s*$")
}
