package dev.sumin.skeleton.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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

    @Test
    fun `refuses to start when the old push advertising keys are set - they would be ignored and ads could go out without a consent check`() {
        for (old in listOf("skeleton.notification.push.advertising-types=promo.x", "skeleton.notification.push.advertising.sender-name=Example")) {
            contextRunner.withPropertyValues(old).run { context ->
                val failure = context.startupFailure
                assertNotNull(failure, old)
                assertTrue(generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("skeleton.notification.advertising") }, old)
            }
        }
    }
}
