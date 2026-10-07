package dev.sumin.skeleton.auth.account

/**
 * 로그인에 필요한 계정 한 줄. [loginBlock] 이 null 이 아니면 비밀번호가 맞아도 토큰을 내지 않는다 (계정 저장소가 상태를 이 값으로 알린다).
 * [passwordHash] 가 비면 비밀번호가 없는 계정(소셜 · 매직 링크만)이다.
 */
data class AuthAccount(
    val accountId: String,
    val username: String,
    val email: String,
    val passwordHash: String,
    val roles: Set<String>,
    val loginBlock: LoginBlock? = null,
    /**
     * [loginBlock] 의 응답에 실을 값 — **인증에 성공해 토큰을 내려던 순간에만** 계산한다 (조회만으로 비밀을 만들지 않게 늦게 부른다).
     * 예: 탈퇴 대기 계정의 `purgeAfter` · 취소 토큰
     */
    val blockData: (() -> Map<String, Any?>)? = null,
) {
    // 비밀번호 해시가 로그에 찍히지 않게
    override fun toString() = "AuthAccount(accountId=$accountId, roles=$roles, loginBlock=$loginBlock, passwordHash=<redacted>)"
}

/** 계정은 있지만 지금은 로그인할 수 없는 이유 */
enum class LoginBlock { EMAIL_NOT_VERIFIED, SUSPENDED, DELETION_PENDING }
