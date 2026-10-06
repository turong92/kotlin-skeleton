package dev.sumin.skeleton.auth.social.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * docs/deploy.md 가 적은 환경변수 이름(`<P>_AUTH_SOCIAL_PROVIDERS_<X>_CLIENT_ID` …)이 **진짜 환경변수로** 제공자 설정에 묶이는가 —
 * Map 의 값(제공자)에서는 `CLIENT_ID` 가 `client.id` 두 칸이 되어 `clientId` 에 닿지 않는다. 모의 서버 시험은 `client-id` 프로퍼티를 직접 줘서 이 문제를 못 본다.
 * 그래서 환경변수 이름이 [AuthSocialEnvironmentAliases] 를 거쳐야 한다.
 */
class AuthSocialEnvironmentVariablesTest {
    private fun bind(env: Map<String, Any>): AuthSocialProperties {
        val sources = MutablePropertySources()
        sources.addLast(SystemEnvironmentPropertySource("systemEnvironment", env.toMutableMap()))
        val aliases = AuthSocialEnvironmentAliases.fromEnvironment(env.mapValues { it.value.toString() })
        if (aliases.isNotEmpty()) sources.addFirst(MapPropertySource("authSocialEnvAliases", aliases))
        return Binder(ConfigurationPropertySources.from(sources)).bind("skeleton.auth-social", AuthSocialProperties::class.java).get()
    }

    @Test
    fun `the documented environment variable names reach the provider settings`() {
        val p = bind(mapOf(
            "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_ENABLED" to "true",
            "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_ID" to "111.apps.googleusercontent.com",
            "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_SECRET" to "s3cret",
            "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_REDIRECT_URI" to "http://localhost:5173/auth/callback",
            "SKELETON_AUTH_SOCIAL_PROVIDERS_KAKAO_ENABLED" to "true",
            "SKELETON_AUTH_SOCIAL_PROVIDERS_KAKAO_CLIENT_ID" to "kakaokey",
            "SKELETON_AUTH_SOCIAL_PROVIDERS_KAKAO_CLIENT_SECRET" to "ksecret",
        ))
        val g = p.providers.getValue("google")
        assertTrue(g.enabled)
        assertEquals("111.apps.googleusercontent.com", g.clientId)
        assertEquals("s3cret", g.clientSecret)
        assertEquals("http://localhost:5173/auth/callback", g.redirectUri)
        assertEquals("kakaokey", p.providers.getValue("kakao").clientId)
        assertEquals("ksecret", p.providers.getValue("kakao").clientSecret)
    }

    @Test
    fun `unrelated variables and an empty value are ignored`() {
        val aliases = AuthSocialEnvironmentAliases.fromEnvironment(mapOf("PATH" to "/bin", "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_ID" to "", "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_ENABLED" to "true"))
        assertEquals(mapOf<String, Any>("skeleton.auth-social.providers.google.enabled" to "true"), aliases)
    }
}
