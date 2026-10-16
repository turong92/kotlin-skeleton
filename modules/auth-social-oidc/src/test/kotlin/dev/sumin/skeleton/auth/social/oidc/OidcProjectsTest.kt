package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderSource
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectRegistry
import java.util.function.Supplier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드: 제공자는 프로젝트마다 따로 만들어지고, discovery · JWKS 보관도 각자의 제공자 안에 있어 섞이지 않는다 */
class OidcProjectsTest {
    private val idpA = FakeIdp()
    private val idpB = FakeIdp()
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, OidcAutoConfiguration::class.java))
        .withBean(ExternalHttpClient::class.java, Supplier { FakeIdp.httpClient() })
        .withPropertyValues(
            "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
            "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
            "skeleton.projects.c.hosts=c.test", "skeleton.projects.c.schema=pc",
        )

    @AfterTest fun close() { idpA.close(); idpB.close() }

    private val a = "skeleton.projects.a.config.auth-social-oidc.providers.microsoft"
    private val b = "skeleton.projects.b.config.auth-social-oidc.providers.microsoft"

    @Test
    fun `each project gets its own provider from its own issuer - nothing is shared between them and a project without a client id has none`() {
        runner.withPropertyValues(
            "$a.issuer=${idpA.issuer}", "$a.client-id=client-a", "$a.client-secret=secret-a",
            "$b.issuer=${idpB.issuer}", "$b.client-id=client-b", "$b.client-secret=secret-b",
        ).run { ctx ->
            assertTrue(ctx.getBeansOfType(OAuthProvider::class.java).isEmpty(), "no app-wide provider beans in project mode")
            val registry = ctx.getBean(ProjectRegistry::class.java)
            val source = ctx.getBean(OAuthProviderSource::class.java)
            val pa = source.providersFor(registry.projects.first { it.id == "a" }).single()
            val pb = source.providersFor(registry.projects.first { it.id == "b" }).single()
            assertEquals("client-a", pa.publicClientId)
            assertEquals("client-b", pb.publicClientId)
            assertNotSame(pa, pb)
            assertEquals(1, idpA.count("/.well-known/openid-configuration"))
            assertEquals(1, idpB.count("/.well-known/openid-configuration"))
            assertTrue(source.providersFor(registry.projects.first { it.id == "c" }).isEmpty())
        }
    }
}
