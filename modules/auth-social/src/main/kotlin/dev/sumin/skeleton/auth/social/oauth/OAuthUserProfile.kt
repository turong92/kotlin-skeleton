package dev.sumin.skeleton.auth.social.oauth

data class OAuthUserProfile(
    val provider: String,
    val providerUserId: String,
    val email: String?,
    val username: String?,
    val displayName: String?,
    /** 제공자가 이 이메일을 **확인했다고** 알려 줬나. 모르면 false — 계정 연결 · 병합은 true 일 때만 이메일을 믿는다 (docs/accounts.md) */
    val emailVerified: Boolean = false,
)
