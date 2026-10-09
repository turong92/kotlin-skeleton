package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.GatePathRule
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor

/**
 * `skeleton.account.login-id.enabled=true` 일 때, 아이디를 아직 정하지 않은 계정으로 인증된 요청을 허용 목록 밖에서 403 `ACCOUNT.LOGIN_ID_REQUIRED` 로 막는다 (읽기 포함, `data` 없음).
 * [EmailRequiredInterceptor] 와 같은 틀이다: 경로는 스프링이 핸들러를 고르는 것과 같은 방식으로 읽고(`/%61pi/...` · `;` · 이중 슬래시로 우회하지 못한다),
 * `/api/` 아래는 핸들러 종류와 무관하게 막고, HEAD 는 GET 의 규칙을 따르고, 익명 · preflight · 정적 자원 · 에러 디스패치는 건드리지 않는다.
 *
 * 열어 두는 길: 두 게이트가 같이 쓰는 고정 목록 + 아이디 정하기 · 실시간 확인 ([GatePathRule.open]) + 설정 `login-id.extra-exclude-paths`(이 게이트에만, 더하기만).
 * 순서는 아이디(-110) → 이메일(-100) → 약관 재동의(0) — 읽은 계정 한 줄은 [GateAccount.ATTRIBUTE] 로 뒤의 게이트에 넘긴다.
 */
class LoginIdRequiredInterceptor(
    config: AccountProperties.LoginId,
    private val accounts: () -> AccountRepository,
) : HandlerInterceptor {
    private val open = GatePathRule.open(loginIdEnabled = true, extra = config.extraExcludePaths, setting = "skeleton.account.login-id.extra-exclude-paths")

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val accountId = GateRequests.callerToCheck(request, handler, open) ?: return true
        val account = accounts().findById(accountId) ?: return true
        request.setAttribute(GateAccount.ATTRIBUTE, account)
        if (account.loginId == null) throw AccountException(AccountErrorCode.LOGIN_ID_REQUIRED)
        return true
    }
}
