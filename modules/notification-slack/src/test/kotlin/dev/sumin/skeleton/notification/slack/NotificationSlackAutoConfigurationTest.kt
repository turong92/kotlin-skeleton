package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import dev.sumin.skeleton.common.logging.RedactionAutoConfiguration
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono

class NotificationSlackAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                dev.sumin.skeleton.common.observability.ObservabilityLinkAutoConfiguration::class.java,
                NotificationSlackAutoConfiguration::class.java,
            ),
        )
        .withBean(ExternalHttpClient::class.java, Supplier { NoopExternalHttpClient() })
        .withBean(NotificationSubscriptionRegistry::class.java, Supplier { NoopSubscriptionRegistry() })

    @Test
    fun `creates Slack beans when module is present`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(SlackNotificationProperties::class.java).size)
            assertEquals(1, context.getBeansOfType(SlackAlertMessageFactory::class.java).size)
            assertEquals(1, context.getBeansOfType(SlackAlertSender::class.java).size)
            assertEquals(1, context.getBeansOfType(SlackExceptionAspect::class.java).size)
            assertEquals(1, context.getBeansOfType(SlackNotificationForwarder::class.java).size)
            assertEquals(1, context.getBeansOfType(dev.sumin.skeleton.common.observability.ObservabilityLinkResolver::class.java).size)
        }
    }

    @Test
    fun `backs off when user provides sender`() {
        contextRunner
            .withBean(SlackAlertSender::class.java, Supplier { SlackAlertSender { } })
            .run { context ->
                assertEquals(1, context.getBeansOfType(SlackAlertSender::class.java).size)
                assertEquals(0, context.getBeansOfType(SlackWebhookAlertSender::class.java).size)
            }
    }

    @Test
    fun `message factory uses configured platform redactor when present`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    RedactionAutoConfiguration::class.java,
                    NotificationSlackAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues("skeleton.redaction.additional-sensitive-names[0]=orderId")
            .withBean(ExternalHttpClient::class.java, Supplier { NoopExternalHttpClient() })
            .withBean(NotificationSubscriptionRegistry::class.java, Supplier { NoopSubscriptionRegistry() })
            .run { context ->
                val factory = context.getBean(SlackAlertMessageFactory::class.java)
                val payload = factory.create(
                    SlackAlert(
                        title = "Order failed",
                        message = "Vendor rejected order",
                        severity = SlackAlertSeverity.ERROR,
                        topic = "orders",
                        fields = mapOf(
                            "orderId" to "order-1",
                            "providerRequestId" to "request-1",
                        ),
                    ),
                )
                val fieldText = payload.blocks.flatMap { it.fields }.joinToString("\n") { it.text }

                assertTrue(fieldText.contains("[REDACTED]"))
                assertTrue(fieldText.contains("request-1"))
                assertFalse(fieldText.contains("order-1"))
            }
    }

    private class NoopExternalHttpClient : ExternalHttpClient {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> post(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.just("ok" as T)

        override fun <T : Any> get(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> postForm(
            clientName: String,
            path: String,
            form: Map<String, String>,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> put(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> patch(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> delete(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")
    }

    private class NoopSubscriptionRegistry : NotificationSubscriptionRegistry {
        override fun subscribe(
            topics: Set<String>,
            subscriber: NotificationSubscriber,
        ): NotificationSubscription =
            object : NotificationSubscription {
                override val id: String = "noop"
                override val topics: Set<String> = topics
                override fun close() = Unit
            }
    }
}
