package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.web.RequestPaths
import org.springframework.web.util.pattern.PathPattern

/**
 * `email.required=true` 에서 주소 없는 계정에게 **열어 두는** 경로. 계정이 주소를 등록 · 로그아웃 · 탈퇴 · 버릴 수 있는 길은 **코드에 고정**이다 —
 * 설정이 목록을 통째로 바꿔 계정을 가두는 일이 없도록, 설정(`extra-exclude-paths`)은 **더하기만** 한다. 해석하지 못한 항목은 기동 실패.
 */
class EmailRequiredRule private constructor(val method: String?, val pattern: PathPattern, val text: String) {
    /** HEAD 는 GET 의 규칙을 따른다 (그래서 규칙의 GET 은 HEAD 도 연다) */
    fun matches(requestMethod: String, path: org.springframework.http.server.PathContainer): Boolean {
        val m = requestMethod.uppercase()
        val methodOk = method == null || method == m || (method == "GET" && m == "HEAD")
        return methodOk && pattern.matches(path)
    }

    companion object {
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        const val SETTING = "skeleton.account.email.extra-exclude-paths"

        /** `METHOD /패턴` 또는 `/패턴` */
        fun parse(entry: String): EmailRequiredRule {
            val parts = entry.trim().split(Regex("\\s+"))
            val (method, path) = when (parts.size) {
                1 -> null to parts[0]
                2 -> parts[0].uppercase() to parts[1]
                else -> fail(entry)
            }
            if (method != null && method !in METHODS) fail(entry)
            if (!path.startsWith("/")) fail(entry)
            val pattern = try { RequestPaths.pattern(path) } catch (_: Exception) { fail(entry) }
            return EmailRequiredRule(method, pattern, entry.trim())
        }

        private fun fail(entry: String): Nothing =
            throw IllegalArgumentException("$SETTING: cannot read \"$entry\" — write `/path/pattern` or `METHOD /path/pattern` (METHOD one of ${METHODS.joinToString("|")}, the path starts with /)")

        /** 주소 없이도 열어 두는 길 — 설정으로 지울 수 없다 */
        val BUILT_IN: List<EmailRequiredRule> = listOf(
            "GET /api/v1/account/me",
            "POST /api/v1/account/email/change",
            "POST /api/v1/account/email/change/confirm",
            "POST /api/v1/account/discard",
            "POST /api/v1/account/delete",
            "POST /api/v1/account/delete/confirmation",
            "/api/v1/auth/**",
            "/api/v1/legal/**",
            "/api/v1/docs/**",
        ).map(::parse)

        /** 막는 범위 */
        val PROTECTED: PathPattern = RequestPaths.pattern("/api/**")
    }
}
