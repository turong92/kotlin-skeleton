package dev.sumin.skeleton.auth.social.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.auth-social")
data class AuthSocialProperties(
    val providers: Map<String, Provider> = emptyMap(),
) {
    data class Provider(
        val enabled: Boolean = false,
        val clientId: String = "",
        val clientSecret: String = "",
        val redirectUri: String? = null,
        val apiBaseUrl: String? = null,
        val tokenBaseUrl: String? = null,
        val profileBaseUrl: String? = null,
        val tokenPath: String? = null,
        val profilePath: String? = null,
        /** Google 만 읽는다: 제공자가 "확인했다"고 한 이메일을 어디까지 믿나 (기본 [EmailTrust.AUTHORITATIVE]). 다른 제공자는 자기 설정을 쓴다 */
        val emailTrust: EmailTrust? = null,
    )

    /**
     * [AUTHORITATIVE] 병합 · 메일함 증명 · 새 계정의 주소는 제공자가 그 주소의 **권위자**일 때만(Google: `@gmail.com`/`@googlemail.com`, 또는 `hd` 가 주소 도메인과 같은 Workspace) — 기본.
     * [VERIFIED_CLAIM] `email_verified: true` 면 곧 권위자로 친다 (옛 동작 — 주소의 주인이 바뀐 비-Gmail 계정이 남의 계정에 붙을 수 있다).
     */
    enum class EmailTrust { AUTHORITATIVE, VERIFIED_CLAIM }
}
