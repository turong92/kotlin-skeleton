package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.GatePathRule
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor

/**
 * `skeleton.account.email.required=true` 일 때, 주소가 아직 없는 계정으로 인증된 요청을 허용 목록 밖에서 403 `ACCOUNT.EMAIL_REQUIRED` 로 막는다.
 * 다른 모듈(board · notes · storage …)의 엔드포인트도 같이 막힌다 — 이 모듈은 그 모듈들을 모르고 **경로**로만 지킨다 (`legal` 의 재동의 인터셉터와 같은 방식, 순서는 이쪽이 먼저).
 *
 *  - 경로는 스프링이 핸들러를 고르는 것과 **같은 방식**(디코드 후 · 세그먼트 단위 · 컨텍스트 경로 제외, [RequestPaths])으로 읽는다 — `/%61pi/...` 로 우회하지 못한다.
 *  - `/api/` 아래는 **핸들러 종류와 무관**하게 막는다 (컨트롤러 메서드든 함수형 라우트든). 빠지는 것은 정적 자원 핸들러(없는 경로는 404 그대로) · 에러 디스패치 · CORS preflight 뿐이다.
 *  - HEAD 는 GET 의 규칙을 따른다.
 *  - 익명 요청은 건드리지 않는다(보안 체인이 401 을 낸다). 요청마다 계정 한 줄 읽기.
 * 열어 두는 경로: 코드에 고정된 목록([GatePathRule.BUILT_IN], 아이디가 켜져 있으면 아이디 정하기 두 길도 — 아이디 게이트와 같은 목록, [GatePathRule.open]) + 설정의 `extra-exclude-paths`(더하기만, 이 게이트에만).
 * 아이디 게이트(순서 -110)가 먼저 읽은 계정 한 줄이 요청에 있으면([GateAccount.ATTRIBUTE]) 다시 읽지 않는다.
 *
 * **`required-when=WRITE`("쓰기 때")**: 판정이 하나 는다 — 메서드가 `GET` · `HEAD` · `OPTIONS` 면 통과. 그 밖의 메서드(`POST` · `PUT` · `PATCH` · `DELETE` 와 TRACE · 모르는 메서드 · 대소문자가 다른 것까지 나머지 전부)는
 * 위 규칙 그대로 허용 목록 밖이면 같은 403. 허용 목록에는 이 방식에서만 `PATCH /api/v1/account/me`(언어 · 시간대 · 닉네임)가 는다. 결제 같은 다른 모듈의 변경 경로는 앱이 `extra-exclude-paths` 에 적지 않는 한 막힌다.
 * 메서드는 `request.method` — 서블릿 필터가 덮어쓴 뒤의 값, 즉 핸들러가 실제로 도는 메서드다. 이 앱에는 `HiddenHttpMethodFilter` 류가 없고(스프링 부트는 기본 꺼짐) `X-HTTP-Method-Override` · `_method` 는 읽지 않는다.
 */
class EmailRequiredInterceptor(
    config: AccountProperties.Email,
    loginIdEnabled: Boolean = false,
    private val accounts: () -> AccountRepository,
) : HandlerInterceptor {
    private val writeMode = config.requiredWhen == AccountProperties.Email.RequiredWhen.WRITE
    private val open = GatePathRule.open(loginIdEnabled, config.extraExcludePaths, GatePathRule.SETTING) + (if (writeMode) GatePathRule.WRITE_MODE_PATHS else emptyList())

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (writeMode && request.method in READ_METHODS) return true
        val accountId = GateRequests.callerToCheck(request, handler, open) ?: return true
        val account = (request.getAttribute(GateAccount.ATTRIBUTE) as? Account)?.takeIf { it.id == accountId } ?: accounts().findById(accountId) ?: return true
        if (account.email == null) throw AccountException(AccountErrorCode.EMAIL_REQUIRED)
        return true
    }

    private companion object {
        /** "쓰기 때" 방식에서 읽기로 치는 메서드 — 글자 그대로(대소문자 구분)이고 이 셋뿐이다. 나머지는 모두 쓰기 */
        val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
