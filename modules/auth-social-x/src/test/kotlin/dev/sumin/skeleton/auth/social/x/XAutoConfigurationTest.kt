package dev.sumin.skeleton.auth.social.x

import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderSource
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.reactive.function.client.WebClient

class XAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(XAutoConfiguration::class.java))
        .withBean(ExternalHttpClient::class.java, Supplier { DefaultExternalHttpClient(WebClient.builder(), OutboundHttpProperties(), DefaultExternalHttpErrorMapper(), emptyList()) })

    @Test
    fun `nothing is created while the client id is empty - the default of every project that did not set X up`() {
        runner.run { assertTrue(it.getBeansOfType(OAuthProvider::class.java).isEmpty()) }
        runner.withPropertyValues("skeleton.auth-social-x.client-id=", "skeleton.auth-social-x.client-secret=s").run { assertTrue(it.getBeansOfType(OAuthProvider::class.java).isEmpty()) }
    }

    @Test
    fun `a client id turns the provider on without any other switch`() {
        runner.withPropertyValues("skeleton.auth-social-x.client-id=abc", "skeleton.auth-social-x.client-secret=s").run { ctx ->
            assertEquals("x", ctx.getBean(OAuthProvider::class.java).providerId)
        }
    }

    @Test
    fun `a client id without a secret stops the application with a message naming the key`() {
        runner.withPropertyValues("skeleton.auth-social-x.client-id=abc").run { ctx ->
            val messages = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(messages.any { "skeleton.auth-social-x.client-secret" in it }, messages.toString())
        }
    }

    @Test
    fun `project mode - a project with a client id has X, one without has none, and there is no app-wide provider bean`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, XAutoConfiguration::class.java))
            .withBean(ExternalHttpClient::class.java, Supplier { DefaultExternalHttpClient(WebClient.builder(), OutboundHttpProperties(), DefaultExternalHttpErrorMapper(), emptyList()) })
            .withPropertyValues(
                "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
                "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
                "skeleton.projects.a.config.auth-social-x.client-id=x-a", "skeleton.projects.a.config.auth-social-x.client-secret=s",
            )
            .run { ctx ->
                assertTrue(ctx.getBeansOfType(OAuthProvider::class.java).isEmpty())
                val registry = ctx.getBean(ProjectRegistry::class.java)
                val source = ctx.getBean(OAuthProviderSource::class.java)
                assertEquals("x-a", source.providersFor(registry.projects.first { it.id == "a" }).single().publicClientId)
                assertTrue(source.providersFor(registry.projects.first { it.id == "b" }).isEmpty())
            }
    }

    @Test
    fun `project mode - a shared client id does not turn X on in a project that did not set its own`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, XAutoConfiguration::class.java))
            .withBean(ExternalHttpClient::class.java, Supplier { DefaultExternalHttpClient(WebClient.builder(), OutboundHttpProperties(), DefaultExternalHttpErrorMapper(), emptyList()) })
            .withPropertyValues(
                "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
                "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
                "skeleton.auth-social-x.client-id=shared-x", "skeleton.auth-social-x.client-secret=shared-s", "skeleton.auth-social-x.redirect-uri=https://a.test/cb",
                "skeleton.projects.a.config.auth-social-x.client-id=x-a", "skeleton.projects.a.config.auth-social-x.client-secret=s",
            )
            .run { ctx ->
                val registry = ctx.getBean(ProjectRegistry::class.java)
                val source = ctx.getBean(OAuthProviderSource::class.java)
                val a = source.providersFor(registry.projects.first { it.id == "a" }).single()
                assertEquals("x-a", a.publicClientId)
                assertEquals(null, a.publicRedirectUri, "the shared redirect URI is not borrowed")
                assertTrue(source.providersFor(registry.projects.first { it.id == "b" }).isEmpty())
            }
    }
}
