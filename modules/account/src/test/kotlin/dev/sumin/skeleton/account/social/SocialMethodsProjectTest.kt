package dev.sumin.skeleton.account.social

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderSource
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/** `GET /auth/methods` 와 로그인 수단 목록이 지금 프로젝트의 제공자만 보인다 — A 에서 켠 제공자가 B 의 화면에 나오지 않는다 */
class SocialMethodsProjectTest {
    private class Fake(override val providerId: String, private val client: String) : OAuthProvider {
        override val publicClientId: String get() = client
        override fun fetchProfile(authorizationCode: String, redirectUri: String?) =
            OAuthUserProfile(provider = providerId, providerUserId = "u", email = null, username = "u", displayName = null)
    }

    private val projects = ProjectRegistry.from(
        StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "t",
                    mapOf(
                        "skeleton.projects.a.hosts" to "a.test", "skeleton.projects.a.schema" to "pa",
                        "skeleton.projects.b.hosts" to "b.test", "skeleton.projects.b.schema" to "pb",
                        "skeleton.projects.a.config.auth-social.providers.kakao.enabled" to "true",
                        "skeleton.projects.a.config.auth-social.providers.kakao.client-id" to "kakao-a",
                        "skeleton.projects.b.config.auth-social.providers.naver.enabled" to "true",
                        "skeleton.projects.b.config.auth-social.providers.naver.client-id" to "naver-b",
                    ),
                ),
            )
        },
    )
    private val registry = OAuthProviderRegistry(
        emptyList(), AuthSocialProperties(), projects,
        listOf("kakao", "naver").map { id -> OAuthProviderSource.ofProvidersKey(projects, id) { Fake(id, it.clientId) } },
    )
    private val a = projects.projects.first { it.id == "a" }
    private val b = projects.projects.first { it.id == "b" }

    @Test
    fun `the methods a project's login page shows are the providers that project switched on`() {
        val source = AccountSocialWebAutoConfiguration().socialMethodsSource(registry)
        assertEquals(listOf("kakao" to "kakao-a"), ProjectContext.runIn(a) { source.enabled().map { it.provider to it.clientId } })
        assertEquals(listOf("naver" to "naver-b"), ProjectContext.runIn(b) { source.enabled().map { it.provider to it.clientId } })
    }

    @Test
    fun `the sign-in method codes are known app-wide at start - the union of the projects' providers, no project needed`() {
        val source = AccountSocialAutoConfiguration().socialSignInMethodSource(registry)
        assertEquals(listOf("kakao", "naver"), source.methods().map { it.code }.sorted())
    }
}
