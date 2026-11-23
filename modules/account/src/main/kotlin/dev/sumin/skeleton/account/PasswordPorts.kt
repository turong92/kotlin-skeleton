package dev.sumin.skeleton.account

/**
 * 비밀번호를 해시하고 맞춰 보는 포트 — `account-password` 가 있을 때만 [AccountCore.passwords] 로 보인다 (`account` 는 그 모듈에 의존하지 않는다).
 * 해시는 `account_identities.secret` 의 `method='password'` 행에 있다. 포트가 없으면 비밀번호 수단이 없는 서비스다:
 * [Reauth] 는 비밀번호 줄을 건너뛰고 메일 코드 · 소셜 길로 가고, 시드 계정은 기동을 멈춘다.
 */
interface PasswordCredentials {
    fun hash(raw: String): String

    /** 어떤 입력에도 던지지 않는다 — 형식이 이상한 해시 · 너무 긴 비밀번호는 그냥 false */
    fun matches(raw: String, hash: String): Boolean
}

/**
 * 비밀번호 재설정 링크를 만들어 주는 고리 — `account-password` 가 있을 때만 빈으로 내놓는다. 재설정 요청과 "이미 계정이 있어요" 메일이 **같은 길**이다:
 * 같은 계정 상태 규칙 · 같은 주소별 한도 · 같은 토큰. [onlyIfNoneOpen]: 열린 토큰이 있으면 만들지 않는다. 줄 수 없으면(상태 · 한도) null.
 */
fun interface ResetLinkIssuer {
    fun issue(account: Account, onlyIfNoneOpen: Boolean): IssuedLink?
}
