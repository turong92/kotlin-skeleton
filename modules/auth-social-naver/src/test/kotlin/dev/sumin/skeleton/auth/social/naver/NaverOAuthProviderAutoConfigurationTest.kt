package dev.sumin.skeleton.auth.social.naver

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono

class NaverOAuthProviderAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NaverOAuthProviderAutoConfiguration::class.java))

    @Test
    fun `does not create provider when naver is disabled`() {
        contextRunner.run { context ->
            assertTrue(context.getBeansOfType(NaverOAuthProvider::class.java).isEmpty())
        }
    }

    @Test
    fun `creates provider when naver is enabled`() {
        contextRunner
            .withBean(ExternalHttpClient::class.java, Supplier { NoopExternalHttpClient() })
            .withPropertyValues(
                "skeleton.auth-social.providers.naver.enabled=true",
                "skeleton.auth-social.providers.naver.client-id=naver-client",
                "skeleton.auth-social.providers.naver.client-secret=naver-secret",
            )
            .run { context ->
                assertEquals(1, context.getBeansOfType(NaverOAuthProvider::class.java).size)
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
