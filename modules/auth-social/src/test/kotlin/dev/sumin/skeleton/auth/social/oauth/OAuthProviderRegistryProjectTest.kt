package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/** 프로젝트 모드: A 에서 켠 제공자는 B 의 목록 · 로그인에 나오지 않고, 제공자 값(client id …)은 프로젝트의 것이다 */
class OAuthProviderRegistryProjectTest {
    private fun registry(vararg extra: Pair<String, String>): ProjectRegistry =
        ProjectRegistry.from(
            StandardEnvironment().apply {
                propertySources.addFirst(
                    MapPropertySource(
                        "t",
                        mapOf(
                            "skeleton.projects.a.hosts" to "a.test", "skeleton.projects.a.schema" to "pa",
                            "skeleton.projects.b.hosts" to "b.test", "skeleton.projects.b.schema" to "pb",
                        ) + extra,
                    ),
                )
            },
        )

    private class Fake(override val providerId: String, val clientId: String) : OAuthProvider {
        override val publicClientId: String get() = clientId
        override fun fetchProfile(authorizationCode: String, redirectUri: String?) =
            OAuthUserProfile(provider = providerId, providerUserId = "u", email = null, username = "u", displayName = null)
    }

    private fun source(id: String, projects: ProjectRegistry) = OAuthProviderSource.ofProvidersKey(projects, id) { Fake(id, it.clientId) }

    private fun <T> Project.run(block: () -> T) = ProjectContext.runIn(this, block)

    @Test
    fun `a provider switched on in project A is not found in project B`() {
        val projects = registry(
            "skeleton.projects.a.config.auth-social.providers.google.enabled" to "true",
            "skeleton.projects.a.config.auth-social.providers.google.client-id" to "client-of-a",
        )
        val registry = OAuthProviderRegistry(emptyList(), AuthSocialProperties(), projects, listOf(source("google", projects)))
        val (a, b) = projects.projects

        assertEquals("client-of-a", (a.run { registry.findEnabled("google") } as Fake).clientId)
        assertNull(b.run { registry.findEnabled("google") })
        assertEquals(listOf("google"), a.run { registry.providers().map { it.providerId } })
        assertEquals(emptyList(), b.run { registry.providers() })
    }

    @Test
    fun `the same provider in both projects keeps each project's own client id and settings`() {
        val projects = registry(
            "skeleton.projects.a.config.auth-social.providers.kakao.enabled" to "true",
            "skeleton.projects.a.config.auth-social.providers.kakao.client-id" to "kakao-a",
            "skeleton.projects.a.config.auth-social.providers.kakao.redirect-uri" to "https://a.test/cb",
            "skeleton.projects.b.config.auth-social.providers.kakao.enabled" to "true",
            "skeleton.projects.b.config.auth-social.providers.kakao.client-id" to "kakao-b",
        )
        val registry = OAuthProviderRegistry(emptyList(), AuthSocialProperties(), projects, listOf(source("kakao", projects)))
        val (a, b) = projects.projects

        assertEquals("kakao-a", (a.run { registry.findEnabled("kakao") } as Fake).clientId)
        assertEquals("kakao-b", (b.run { registry.findEnabled("kakao") } as Fake).clientId)
        assertEquals("https://a.test/cb", a.run { registry.settings("kakao") }?.redirectUri)
        assertNull(b.run { registry.settings("kakao") }?.redirectUri)
        assertSame(a.run { registry.findEnabled("kakao") }, a.run { registry.findEnabled("kakao") }, "built once per project")
    }

    @Test
    fun `providers the app provides itself are shared, and the enabled switch is the project's`() {
        val projects = registry("skeleton.projects.a.config.auth-social.providers.fake.enabled" to "true")
        val registry = OAuthProviderRegistry(listOf(Fake("fake", "x")), AuthSocialProperties(), projects, emptyList())
        val (a, b) = projects.projects

        assertNotNull(a.run { registry.findEnabled("fake") })
        assertNull(b.run { registry.findEnabled("fake") })
    }

    @Test
    fun `a project that cannot build its providers fails the start and says which project`() {
        val projects = registry()
        val broken = OAuthProviderSource { project -> if (project.id == "b") error("client id is blank") else emptyList() }
        val e = assertFailsWith<IllegalStateException> { OAuthProviderRegistry(emptyList(), AuthSocialProperties(), projects, listOf(broken)) }
        assertEquals(true, e.message!!.contains("'b'") && e.message!!.contains("client id is blank"), e.message)
    }
}
