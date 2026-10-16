package dev.sumin.skeleton.auth.social.google

import dev.sumin.skeleton.auth.social.oauth.OAuthProviderSource
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono

class GoogleOAuthProviderAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(GoogleOAuthProviderAutoConfiguration::class.java))

    @Test
    fun `does not create provider when google is disabled`() {
        contextRunner.run { context ->
            assertTrue(context.getBeansOfType(GoogleOAuthProvider::class.java).isEmpty())
        }
    }

    @Test
    fun `creates provider when google is enabled`() {
        contextRunner
            .withBean(ExternalHttpClient::class.java, Supplier { NoopExternalHttpClient() })
            .withPropertyValues(
                "skeleton.auth-social.providers.google.enabled=true",
                "skeleton.auth-social.providers.google.client-id=google-client",
                "skeleton.auth-social.providers.google.client-secret=google-secret",
            )
            .run { context ->
                assertEquals(1, context.getBeansOfType(GoogleOAuthProvider::class.java).size)
            }
    }

    @Test
    fun `project mode - each project turns Google on with its own client, and no app-wide provider bean exists`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, GoogleOAuthProviderAutoConfiguration::class.java))
            .withBean(ExternalHttpClient::class.java, Supplier { NoopExternalHttpClient() })
            .withPropertyValues(
                "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
                "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
                "skeleton.auth-social.providers.google.client-secret=shared-secret",
                "skeleton.projects.a.config.auth-social.providers.google.enabled=true",
                "skeleton.projects.a.config.auth-social.providers.google.client-id=google-a",
                "skeleton.projects.a.config.auth-social.providers.google.redirect-uri=https://a.test/cb",
            )
            .run { context ->
                assertTrue(context.getBeansOfType(GoogleOAuthProvider::class.java).isEmpty())
                val registry = context.getBean(ProjectRegistry::class.java)
                val source = context.getBean(OAuthProviderSource::class.java)
                val a = source.providersFor(registry.projects.first { it.id == "a" }).single()
                assertEquals("google-a", a.publicClientId)
                assertEquals("https://a.test/cb", a.publicRedirectUri)
                assertTrue(source.providersFor(registry.projects.first { it.id == "b" }).isEmpty(), "B did not turn it on")
            }
    }

    private class NoopExternalHttpClient : ExternalHttpClient {
        override fun <T : Any> get(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())

        override fun <T : Any> post(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())

        override fun <T : Any> postForm(
            clientName: String,
            path: String,
            form: Map<String, String>,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())

        override fun <T : Any> put(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())

        override fun <T : Any> patch(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())

        override fun <T : Any> delete(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())
    }
}
