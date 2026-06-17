package dev.sumin.skeleton.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class NotificationAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))

    @Test
    fun `creates in memory broker by default`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(NotificationBroker::class.java).size)
            assertEquals(1, context.getBeansOfType(NotificationPublisher::class.java).size)
            assertEquals(1, context.getBeansOfType(NotificationSubscriptionRegistry::class.java).size)
            assertEquals(1, context.getBeansOfType(NotificationInboxRepository::class.java).size)
            assertEquals(1, context.getBeansOfType(NotificationRecipientResolver::class.java).size)
        }
    }
}
