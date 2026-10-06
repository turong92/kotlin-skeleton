package dev.sumin.skeleton.auth.social.oauth

data class OAuthUserProfile(
    val provider: String,
    val providerUserId: String,
    val email: String?,
    val username: String?,
    val displayName: String?,
    /** 제공자가 이 이메일을 **확인했다고** 알려 줬나. 모르면 false — 계정 연결 · 병합은 true 일 때만 이메일을 믿는다 (docs/accounts.md) */
    val emailVerified: Boolean = false,
    /** 제공자의 프로필 사진 주소 (https). 없으면 null. 계정은 아직 저장하지 않는다 — 앱이 정책을 갈아 끼워 쓴다 */
    val avatarUrl: String? = null,
)
