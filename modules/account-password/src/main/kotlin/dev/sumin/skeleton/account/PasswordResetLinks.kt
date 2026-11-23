package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.token.TokenPurposes

/**
 * 비밀번호 재설정 링크를 만든다 — 재설정 요청([PasswordService.forgot])과 "이미 계정이 있어요" 메일(`account` 의 [ResetLinkIssuer] 고리)이 **같은 길**이다:
 * 같은 계정 상태 규칙(ACTIVE · PENDING_VERIFICATION 만), 같은 주소별 한도(`reset.per-email`), 같은 토큰(새 토큰이 이전 것을 닫는다). 못 만들면(상태 · 한도) null.
 */
class PasswordResetLinks(private val core: AccountCore) : ResetLinkIssuer {
    override fun issue(account: Account, onlyIfNoneOpen: Boolean): IssuedLink? {
        if (account.status != AccountStatus.ACTIVE && account.status != AccountStatus.PENDING_VERIFICATION) return null
        val email = account.email ?: return null
        // 남의 요청이 낳는 링크(이미 계정이 있어요 메일)는 주인이 연 링크를 닫지도, 한도를 쓰지도 않는다 — 열린 토큰이 있으면 만들지 않는다
        if (onlyIfNoneOpen && core.tokens.hasOpen(TokenPurposes.PASSWORD_RESET, email)) return null
        val r = core.props.reset
        if (!core.limits.acquire("reset:email", email, r.perEmail, r.perEmailWindow).allowed) return null
        val raw = core.tokens.issue(TokenPurposes.PASSWORD_RESET, email, account.id, r.ttl)
        return IssuedLink(core.links.reset(raw), r.ttl.toMinutes())
    }
}
