package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import java.security.Principal
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

class NotificationWebSocketAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationWebSocketAutoConfiguration::class.java))
        .withBean(NotificationSubscriptionRegistry::class.java, Supplier { NoopSubscriptionRegistry() })

    @Test
    fun `does not create websocket beans when disabled`() {
        contextRunner
            .withPropertyValues("skeleton.notification-websocket.enabled=false")
            .run { context ->
                assertTrue(context.getBeansOfType(NotificationWebSocketAuthenticationInterceptor::class.java).isEmpty())
                assertTrue(context.getBeansOfType(WebSocketMessageBrokerConfigurer::class.java).isEmpty())
            }
    }

    @Test
    fun `creates websocket beans by default when module is present`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(NotificationWebSocketProperties::class.java).size)
            assertEquals(1, context.getBeansOfType(NotificationWebSocketAuthenticationInterceptor::class.java).size)
            assertEquals(1, context.getBeansOfType(NotificationWebSocketDestinationResolver::class.java).size)
            assertEquals(1, context.getBeansOfType(NotificationWebSocketBridge::class.java).size)
            assertEquals(1, context.getBeansOfType(WebSocketMessageBrokerConfigurer::class.java).size)
        }
    }

    @Test
    fun `binds endpoint heartbeat transport and executor properties`() {
        contextRunner
            .withPropertyValues(
                "skeleton.notification-websocket.endpoint.path=/realtime",
                "skeleton.notification-websocket.endpoint.allowed-origin-patterns=https://app.example.com",
                "skeleton.notification-websocket.endpoint.sock-js-enabled=true",
                "skeleton.notification-websocket.heartbeat.server-interval=15s",
                "skeleton.notification-websocket.heartbeat.client-interval=20s",
                "skeleton.notification-websocket.inbound-channel.core-pool-size=2",
                "skeleton.notification-websocket.inbound-channel.max-pool-size=6",
                "skeleton.notification-websocket.inbound-channel.queue-capacity=50",
                "skeleton.notification-websocket.outbound-channel.queue-capacity=75",
                "skeleton.notification-websocket.transport.message-size-limit=128KB",
                "skeleton.notification-websocket.transport.send-buffer-size-limit=1MB",
                "skeleton.notification-websocket.transport.send-time-limit=25s",
                "skeleton.notification-websocket.broker.user-destination-prefix=/users",
            )
            .run { context ->
                val properties = context.getBean(NotificationWebSocketProperties::class.java)

                assertEquals("/realtime", properties.endpoint.path)
                assertEquals(listOf("https://app.example.com"), properties.endpoint.allowedOriginPatterns)
                assertTrue(properties.endpoint.sockJsEnabled)
                assertEquals(15_000, properties.heartbeat.serverInterval.toMillis())
                assertEquals(20_000, properties.heartbeat.clientInterval.toMillis())
                assertEquals(2, properties.inboundChannel.corePoolSize)
                assertEquals(6, properties.inboundChannel.maxPoolSize)
                assertEquals(50, properties.inboundChannel.queueCapacity)
                assertEquals(75, properties.outboundChannel.queueCapacity)
                assertEquals(128L * 1024, properties.transport.messageSizeLimit.toBytes())
                assertEquals(1024L * 1024, properties.transport.sendBufferSizeLimit.toBytes())
                assertEquals(25_000, properties.transport.sendTimeLimit.toMillis())
                assertEquals("/users", properties.broker.userDestinationPrefix)
            }
    }

    @Test
    fun `uses user provided token verifier bean`() {
        contextRunner
            .withPropertyValues("skeleton.notification-websocket.authentication.enabled=true")
            .withBean(NotificationWebSocketTokenVerifier::class.java, Supplier {
                NotificationWebSocketTokenVerifier { TestPrincipal("verified-user") }
            })
            .run { context ->
                val interceptor = context.getBean(NotificationWebSocketAuthenticationInterceptor::class.java)

                assertEquals("verified-user", interceptor.verifyForTest("token")?.name)
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

    private data class TestPrincipal(
        private val principalName: String,
    ) : Principal {
        override fun getName(): String = principalName
    }
}
