package dev.sumin.skeleton.auth.social.x

import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
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
}
