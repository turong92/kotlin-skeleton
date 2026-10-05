package dev.sumin.skeleton.common.logging

/**
 * 로그 **출력 끝**의 마지막 가림막. 부르는 쪽이 [SensitiveValueRedactor] 를 잊어도 완성된 줄에서 지운다. 문장 속 값은 이름이 없으니 **모양**으로 찾는다.
 *
 * 기본 규칙(항상):
 * - `Authorization` · `Cookie` · `Set-Cookie` 헤더 줄 → 값 전체
 * - `Bearer <값>`
 * - JWT 모양(`eyJ….….…`)
 * - `…token` · `…secret` · `…password` · `apiKey` 이름의 `이름=값` · `"이름":"값"` 쌍
 *
 * 더하는 것: [maskEmails] (`a***@d***.com`), 앱이 정한 [patterns] — 정규식, 일치한 부분 전체를 [replacement] 로 바꾸되 이름 있는 그룹 `value` 가 있으면 그 그룹만.
 * 줄의 나머지(traceId · 시각 · 일반 단어)는 그대로 둔다. 설정은 `skeleton.redaction.output.*`.
 */
class LogMasker(
    patterns: List<String> = emptyList(),
    private val replacement: String = DEFAULT_REPLACEMENT,
    private val maskEmails: Boolean = false,
) {
    private val appPatterns: List<Regex> = patterns.map { pattern ->
        try {
            Regex(pattern)
        } catch (ex: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid skeleton.redaction.output.patterns entry '$pattern': ${ex.message}", ex)
        }
    }

    fun mask(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = HEADER_LINE.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$replacement" }
        out = BEARER.replace(out) { "Bearer $replacement" }
        out = JWT.replace(out, replacement)
        out = SENSITIVE_PAIR.replace(out) { "${it.groupValues[1]}$replacement" }
        appPatterns.forEach { regex -> out = replaceApp(regex, out) }
        if (maskEmails) out = EMAIL.replace(out) { maskEmail(it.value) }
        return out
    }

    private fun replaceApp(regex: Regex, text: String): String = regex.replace(text) { match ->
        val value = runCatching { match.groups["value"] }.getOrNull()
        if (value == null) {
            replacement
        } else {
            val start = value.range.first - match.range.first
            val end = value.range.last + 1 - match.range.first
            match.value.substring(0, start) + replacement + match.value.substring(end)
        }
    }

    companion object {
        const val DEFAULT_REPLACEMENT = "[REDACTED]"

        private val HEADER_LINE = Regex("""(?i)\b(authorization|set-cookie|cookie)(\s*:\s*)[^\r\n]*""")
        private val BEARER = Regex("""(?i)\bBearer\s+[^\s"',;}]+""")
        private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*""")
        private val SENSITIVE_PAIR = Regex(
            """(?i)((["']?)\b[a-z0-9_-]*(?:token|secret|password|passwd|apikey|api-key|api_key)\2\s*[=:]\s*["']?)[^\s&,;"'}]+""",
        )
        private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")

        /** logback 변환기가 쓰는 가림막. 스프링 컨텍스트가 뜨면 설정으로 만든 것으로 바뀐다 — 그 전 줄은 기본 규칙만 */
        @Volatile
        var current: LogMasker = LogMasker()
            private set

        fun install(masker: LogMasker) {
            current = masker
        }

        /** `alice@domain.com` → `a***@d***.com`, `user@example.co.kr` → `u***@e***.kr` */
        fun maskEmail(email: String): String {
            val at = email.lastIndexOf('@')
            if (at <= 0 || at == email.length - 1) return DEFAULT_REPLACEMENT
            val local = email.substring(0, at)
            val labels = email.substring(at + 1).split('.')
            return "${local.first()}***@${labels.first().first()}***.${labels.last()}"
        }
    }
}
