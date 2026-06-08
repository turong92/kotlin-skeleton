package dev.sumin.skeleton.notification.sse

import dev.sumin.skeleton.common.web.PublicEndpointRegistry
import dev.sumin.skeleton.common.web.WebProperties
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpMethod

class NotificationSseAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationSseAutoConfiguration::class.java))
        .withBean(NotificationSubscriptionRegistry::class.java, Supplier { NoopSubscriptionRegistry() })

    @Test
    fun `does not create sse beans when disabled`() {
        contextRunner
            .withPropertyValues("skeleton.notification.sse.enabled=false")
            .run { context ->
                assertTrue(context.getBeansOfType(NotificationSseController::class.java).isEmpty())
                assertTrue(context.getBeansOfType(NotificationSseService::class.java).isEmpty())
            }
    }

    @Test
    fun `creates sse controller and service by default when module is present`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(NotificationSseController::class.java).size)
            assertEquals(1, context.getBeansOfType(NotificationSseService::class.java).size)
        }
    }

    @Test
    fun `contributes public endpoint only when enabled`() {
        contextRunner
            .withPropertyValues("skeleton.notification.sse.public-endpoint=true")
            .run { context ->
                val contributors = context.getBeansOfType(dev.sumin.skeleton.common.web.PublicEndpointContributor::class.java)
                    .values
                val registry = PublicEndpointRegistry.from(WebProperties(), contributors)
                val endpoint = registry.methodSpecificEndpoints.single {
                    it.pattern == "/api/v1/notifications/sse"
                }

                assertEquals(HttpMethod.GET, endpoint.method)
            }
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
