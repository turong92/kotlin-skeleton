package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.web.RequestPaths
import org.springframework.web.util.pattern.PathPattern

/**
 * 계정이 아직 채우지 못한 것(이메일 · 로그인 아이디)을 요구하는 **게이트들이 하나로 쓰는** 경로 규칙. `email.required=true` 의 이메일 게이트(`EmailRequiredInterceptor`)와
 * `login-id.enabled=true` 의 아이디 게이트(`LoginIdRequiredInterceptor`)가 같은 해석기 · 같은 고정 목록 · 같은 막는 범위를 쓴다.
 *
 * 계정이 빠져나가는 길(등록 · 로그아웃 · 탈퇴 · 버리기)은 **코드에 고정**이다 — 설정이 목록을 통째로 바꿔 계정을 가두는 일이 없도록, 설정(`extra-exclude-paths`)은 **더하기만** 한다.
 * 해석하지 못한 항목은 기동 실패. 각 게이트의 `extra-exclude-paths` 는 **자기 게이트에만** 더해진다.
 */
class GatePathRule private constructor(val method: String?, val pattern: PathPattern, val text: String) {
    /** HEAD 는 GET 의 규칙을 따른다 (그래서 규칙의 GET 은 HEAD 도 연다) */
    fun matches(requestMethod: String, path: org.springframework.http.server.PathContainer): Boolean {
        val m = requestMethod.uppercase()
        val methodOk = method == null || method == m || (method == "GET" && m == "HEAD")
        return methodOk && pattern.matches(path)
    }

    companion object {
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

        /** 이메일 게이트의 설정 이름 — 해석 오류 문구에 쓰인다 (아이디 게이트는 자기 이름을 [parse] 에 넘긴다) */
        const val SETTING = "skeleton.account.email.extra-exclude-paths"

        /** `METHOD /패턴` 또는 `/패턴` */
        fun parse(entry: String, setting: String = SETTING): GatePathRule {
            val parts = entry.trim().split(Regex("\\s+"))
            val (method, path) = when (parts.size) {
                1 -> null to parts[0]
                2 -> parts[0].uppercase() to parts[1]
                else -> fail(entry, setting)
            }
            if (method != null && method !in METHODS) fail(entry, setting)
            if (!path.startsWith("/")) fail(entry, setting)
            val pattern = try { RequestPaths.pattern(path) } catch (_: Exception) { fail(entry, setting) }
            return GatePathRule(method, pattern, entry.trim())
        }

        private fun fail(entry: String, setting: String): Nothing =
            throw IllegalArgumentException("$setting: cannot read \"$entry\" — write `/path/pattern` or `METHOD /path/pattern` (METHOD one of ${METHODS.joinToString("|")}, the path starts with /)")

        /** 두 게이트가 **늘** 여는 길 — 설정으로 지울 수 없다 */
        val BUILT_IN: List<GatePathRule> = listOf(
            "GET /api/v1/account/me",
            "POST /api/v1/account/email/change",
            "POST /api/v1/account/email/change/confirm",
            "POST /api/v1/account/discard",
            "POST /api/v1/account/delete",
            "POST /api/v1/account/delete/confirmation",
            "/api/v1/auth/**",
            "/api/v1/legal/**",
            "/api/v1/docs/**",
        ).map { parse(it) }

        /** 아이디가 켜져 있을 때 **더** 여는 길 — 아이디 정하기와 그 실시간 확인 (아이디 게이트가 막은 계정이 이 두 길로 풀린다. 이메일이 없어도 정할 수 있으므로 이메일 게이트도 연다) */
        val LOGIN_ID_PATHS: List<GatePathRule> = listOf(
            "POST /api/v1/account/login-id",
            "POST /api/v1/account/login-id/availability",
        ).map { parse(it) }

        /**
         * "쓰기 때"(`required-when=WRITE`) 방식에서만 이메일 게이트가 **더** 여는 길 — 자기 계정 살림(언어 · 시간대 · 닉네임)과 소셜 로그인 수단의 연결 · 해제. 로그인 직후 방식에서는 열리지 않는다.
         * 연결 · 해제는 서버가 다시 인증(이미 연결된 제공자의 새 코드)을 요구하고, 마지막 수단 보호 · 정지 계정 거절 · 제공자당 하나는 그대로다. 비밀번호 만들기는 열지 않는다(재설정이 이메일로 가므로).
         */
        val WRITE_MODE_PATHS: List<GatePathRule> = listOf(
            "PATCH /api/v1/account/me",
            "POST /api/v1/account/identities/social/*",
            "DELETE /api/v1/account/identities/*",
        ).map { parse(it) }

        /**
         * 한 게이트가 여는 전체 목록 = [BUILT_IN] + (아이디가 켜져 있으면 [LOGIN_ID_PATHS]) + 그 게이트 자기 설정의 [extra].
         * 이메일 게이트 · 아이디 게이트가 같은 함수로 만든다 — 목록이 한쪽만 자라는 일이 없다.
         */
        fun open(loginIdEnabled: Boolean, extra: List<String>, setting: String): List<GatePathRule> =
            BUILT_IN + (if (loginIdEnabled) LOGIN_ID_PATHS else emptyList()) + extra.map { parse(it, setting) }

        /** 막는 범위 */
        val PROTECTED: PathPattern = RequestPaths.pattern("/api/**")
    }
}

/** 이전 이름 — 이메일 게이트만 있던 때의 이름이다. 해석기 · 고정 목록은 [GatePathRule] 하나를 두 게이트가 쓴다 */
typealias EmailRequiredRule = GatePathRule
