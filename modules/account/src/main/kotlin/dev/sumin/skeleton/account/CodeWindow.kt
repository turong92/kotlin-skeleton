package dev.sumin.skeleton.account

import java.time.Instant

/**
 * 코드(6자리 인증번호)를 보낸 응답이 프론트의 카운트다운에 주는 두 시각 — [expiresAt] 코드가 만료되는 때 · [resendAvailableAt] 새 코드를 다시 청할 수 있게 되는 때.
 * **존재를 노출하지 않는다**: 계정이 이미 있어 메일 종류가 달랐거나, 한도 · 이미 쓰는 주소라 메일을 아예 안 보냈거나, 보낼 주소가 없어도 같은 모양 · 같은 계산이다.
 * [resendAvailableAt] 은 가입 시도만 서버가 강제한다(`verification.resend-cooldown`) — 나머지는 화면이 버튼을 잠시 쉬게 하는 안내다.
 */
data class CodeWindow(val expiresAt: Instant, val resendAvailableAt: Instant)

/** 링크 하나와 그 유효 시간(분) — 메일 본문에 쓴다. 링크에는 토큰이 있으므로 [toString] 은 가린다 */
data class IssuedLink(val url: String, val minutes: Long) {
    override fun toString() = "IssuedLink(url=<redacted>, minutes=$minutes)"
}

/**
 * 1회용 매직 링크를 만들어 주는 고리 — `auth-magic-link` 가 있을 때만 그 모듈이 빈으로 내놓는다. `account` 는 그 모듈에 의존하지 않고, 이 빈이 있으면
 * "이미 계정이 있어요" 메일에 로그인 링크 한 줄을 더할 뿐이다. 구현은 매직 링크 요청과 같은 한도(주소별 메일 수)를 쓴다. 줄 수 없으면(한도 · 계정 상태) null.
 */
fun interface MagicLinkIssuer {
    fun issue(account: Account): IssuedLink?
}
