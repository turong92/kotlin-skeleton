package dev.sumin.skeleton.auth.social.oauth

data class OAuthUserProfile(
    val provider: String,
    val providerUserId: String,
    val email: String?,
    val username: String?,
    val displayName: String?,
    /** 제공자가 이 이메일을 **확인했다고** 알려 줬나. 모르면 false — 계정 연결 · 병합은 true 일 때만 이메일을 믿는다 (docs/accounts.md) */
    val emailVerified: Boolean = false,
    /**
     * 제공자가 **그 주소의 권위자**인가 — [emailVerified] 보다 강하다. 계정 병합 · 메일함 증명 · 새 계정에 주소 저장은 이것만 본다.
     * "확인했다"가 곧 "지금도 이 사람 것"이 아닌 제공자(Google: 비-Gmail · `hd` 없는 주소)는 [emailVerified]=true, 이것은 false. 기본은 [emailVerified] 와 같다.
     */
    val emailAuthoritative: Boolean = emailVerified,
    /** 제공자의 프로필 사진 주소 (https). 없으면 null. 계정은 아직 저장하지 않는다 — 앱이 정책을 갈아 끼워 쓴다 */
    val avatarUrl: String? = null,
)
