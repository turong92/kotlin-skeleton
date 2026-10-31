package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PushAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, PushAutoConfiguration::class.java))

    @Test
    fun `off by default - adds no push bean and subscribes nothing`() {
        runner.run { context ->
            assertEquals(0, context.getBeansOfType(PushNotificationChannel::class.java).size)
            assertEquals(0, context.getBeansOfType(PushDeliveryWorker::class.java).size)
        }
    }

    @Test
    fun `enabled without a device store fails to start and tells which module to add`() {
        runner.withPropertyValues("skeleton.notification.push.enabled=true").run { context ->
            val message = generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("notification-push-jdbc" in message, message)
        }
    }

    @Test
    fun `enabled with the three ports wires channel and worker`() {
        runner.withPropertyValues("skeleton.notification.push.enabled=true")
            .withBean(PushDeviceStore::class.java, { FakePushDeviceStore() })
            .withBean(PushTypeMuteStore::class.java, { FakePushTypeMuteStore() })
            .withBean(PushTasks::class.java, { RecordingPushTasks() })
            .run { context ->
                assertEquals(1, context.getBeansOfType(PushNotificationChannel::class.java).size)
                assertEquals(1, context.getBeansOfType(PushDeliveryWorker::class.java).size)
            }
    }
}
