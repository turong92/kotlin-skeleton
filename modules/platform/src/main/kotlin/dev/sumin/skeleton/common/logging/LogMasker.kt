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
 *
 * **요청 로그의 경로는 따로다**([maskRequestPath]): 인증 · 한도보다 앞에서 아무 호출자의 경로를 받으므로 위의 기본 규칙 · 설정 규칙을 태우지 않고,
 * [pathPatterns](= [LogMaskingContributor] 가 내놓은 규칙)만 길이를 자른 뒤에 적용한다. 기본 규칙의 반복은 모두 유한이다 — 입력 길이에 제곱으로 느려지지 않게.
 */
class LogMasker(
    patterns: List<String> = emptyList(),
    private val replacement: String = DEFAULT_REPLACEMENT,
    private val maskEmails: Boolean = false,
    /** 요청 로그의 경로에만 적용하는 규칙(기여자의 것). [patterns] 에도 들어 있어야 logback 줄에서 같이 돈다 — 여기에는 그중 경로에 태울 것만. */
    pathPatterns: List<String> = emptyList(),
) {
    private val appPatterns: List<Regex> = compile(patterns)
    private val pathRules: List<Regex> = compile(pathPatterns)

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

    /**
     * 요청 로그에 찍을 경로. 규칙([pathPatterns])이 없으면 **경로 그대로**(이 가림막이 요청 로그에 닿기 전과 글자까지 같다 — 정규식도 안 돈다).
     * 있으면 [MAX_PATH_CHARS] 자로 **먼저 자르고**(잘렸으면 끝에 [TRUNCATED_MARK]) 그 규칙만 적용한다 — 기본 규칙은 태우지 않는다.
     */
    fun maskRequestPath(path: String): String {
        if (pathRules.isEmpty()) return path
        val cut = path.length > MAX_PATH_CHARS
        var out = if (cut) path.substring(0, MAX_PATH_CHARS) else path
        pathRules.forEach { regex -> out = replaceApp(regex, out) }
        return if (cut) out + TRUNCATED_MARK else out
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

        /** 요청 로그의 경로를 규칙에 넣기 전에 자르는 길이 — 기여자의 규칙이 느린 정규식이어도 입력이 묶인다 */
        const val MAX_PATH_CHARS = 512
        const val TRUNCATED_MARK = "...[truncated]"

        private fun compile(patterns: List<String>): List<Regex> = patterns.map { pattern ->
            try {
                Regex(pattern)
            } catch (ex: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid skeleton.redaction.output.patterns entry '$pattern': ${ex.message}", ex)
            }
        }

        private val HEADER_LINE = Regex("""(?i)\b(authorization|set-cookie|cookie)(\s*:\s*)[^\r\n]*""")
        private val BEARER = Regex("""(?i)\bBearer\s+[^\s"',;}]+""")
        // 반복은 모두 유한 + 소유형(+): 입력이 길어도 제곱으로 느려지지 않는다. 헤더 512 · 내용 8192 · 서명 2048 은 보통의 JWT 보다 넉넉하다.
        // 그 상한을 넘는 덩어리(x5c 인증서 사슬이 든 헤더, 큰 내용 · 서명)는 앞의 긴 덩어리 규칙이 `eyJ` 부터 512자 넘게 이어지는 JWT 글자(점 포함)를 통째로 가린다 —
        // 성공한 일치는 소비되므로 선형이 유지되고, 상한에 걸려 일치가 실패해 평문으로 남는 일이 없다
        private val JWT = Regex(
            """\beyJ[A-Za-z0-9_.-]{512,}+|\beyJ[A-Za-z0-9_-]{5,512}+\.[A-Za-z0-9_-]{5,8192}+\.[A-Za-z0-9_-]{0,2048}+""",
        )

        // 키 이름 앞 조각은 64자까지(`\b` 는 뺀다 — 이름이 더 길어도 키워드 앞 64자에서 시작해 같은 값을 가린다). 닫는 따옴표는 있어도 없어도 된다:
        // 이름 중간에서 시작하면 여는 따옴표를 못 잡아도 닫는 따옴표(`"…_token":`)는 만난다
        private val SENSITIVE_PAIR = Regex(
            """(?i)(["']?[a-z0-9_-]{0,64}(?:token|secret|password|passwd|apikey|api-key|api_key)["']?\s*[=:]\s*["']?)[^\s&,;"'}]+""",
        )
        private val EMAIL = Regex("""[A-Za-z0-9._%+-]{1,64}+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")

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
