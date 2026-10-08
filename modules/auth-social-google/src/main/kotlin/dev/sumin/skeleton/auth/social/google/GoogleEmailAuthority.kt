package dev.sumin.skeleton.auth.social.google

import tools.jackson.databind.json.JsonMapper
import java.util.Base64

/**
 * Google 이 `email_verified: true` 를 준 주소 가운데, **Google 이 지금도 그 사람 것이라고 보증하는** 것만 가린다.
 * Google 계정은 비-Gmail 주소로도 만들 수 있고, 만들 때 한 번 확인한 뒤 주소의 주인이 바뀌어도 계속 `email_verified: true` 를 준다.
 * Google 의 안내: `@gmail.com` 이거나, `hd`(Workspace 호스팅 도메인)가 있을 때만 확신할 수 있다. 우리는 `hd` 가 주소 도메인과 **같을 때**만 인정한다.
 */
internal object GoogleEmailAuthority {
    private val GMAIL = setOf("gmail.com", "googlemail.com")

    /** [email] 은 userinfo 의 것, [hostedDomain] 은 userinfo 와 맞춰 본 ID 토큰의 `hd` (없으면 null). 호출자가 `email_verified` 를 이미 확인했다 */
    fun authoritative(email: String?, hostedDomain: String?): Boolean {
        val domain = domainOf(email) ?: return false
        if (domain in GMAIL) return true
        val hd = hostedDomain?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && !it.endsWith(".") } ?: return false
        return hd == domain
    }

    /** 정확히 하나의 `@`, 비지 않은 로컬 · 도메인, 끝점 없음 — 아니면 null (안 믿는다). 소문자로 */
    private fun domainOf(email: String?): String? {
        val e = email?.trim()?.lowercase() ?: return null
        if (e.count { it == '@' } != 1) return null
        val (local, domain) = e.split('@')
        if (local.isEmpty() || domain.isEmpty() || domain.endsWith(".") || domain.startsWith(".")) return null
        return domain
    }
}

/** ID 토큰(JWT)의 payload 를 문자열 클레임으로 — 서명은 보지 않는다 ([GoogleOAuthProvider] 가 쓰는 자리에서 근거를 적었다) */
internal object GoogleIdToken {
    private val mapper = JsonMapper.builder().build()

    fun claims(jwt: String?): Map<String, String>? {
        val parts = jwt?.split('.') ?: return null
        if (parts.size != 3) return null
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            mapper.readTree(json).properties().filter { it.value.isTextual }.associate { it.key to it.value.asText() }
        }.getOrNull()
    }
}
