package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.notification.advertising.AdvertisingConsent
import dev.sumin.skeleton.notification.advertising.AdvertisingConsentPolicy
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
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

    private val withPorts = runner.withPropertyValues("skeleton.notification.push.enabled=true", "skeleton.notification.advertising.types=promo.tips")
        .withBean(PushDeviceStore::class.java, { FakePushDeviceStore() })
        .withBean(PushTypeMuteStore::class.java, { FakePushTypeMuteStore() })
        .withBean(PushTasks::class.java, { RecordingPushTasks() })

    private fun failureOf(context: org.springframework.context.ApplicationContext) =
        generateSequence((context as org.springframework.boot.test.context.assertj.AssertableApplicationContext).startupFailure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `advertising types without a consent policy fail to start and tell which module to add`() {
        withPorts.withPropertyValues("skeleton.notification.advertising.fallback-zone=Asia/Seoul", "skeleton.notification.advertising.sender-name=Example Co.")
            .run { context -> assertTrue("notification-legal" in failureOf(context), failureOf(context)) }
    }

    @Test
    fun `advertising types without fallback-zone or sender-name, or with a ttl under twelve hours, fail to start`() {
        val policy = AdvertisingConsentPolicy { _, _ -> AdvertisingConsent(send = false, night = false) }
        val base = withPorts.withBean(AdvertisingConsentPolicy::class.java, { policy })

        base.run { context -> assertTrue("fallback-zone" in failureOf(context), failureOf(context)) }
        base.withPropertyValues("skeleton.notification.advertising.fallback-zone=Asia/Seoul").run { context -> assertTrue("sender-name" in failureOf(context), failureOf(context)) }
        base.withPropertyValues("skeleton.notification.advertising.fallback-zone=Asia/Seoul", "skeleton.notification.advertising.sender-name=x", "skeleton.notification.push.ttl=PT11H")
            .run { context -> assertTrue("ttl" in failureOf(context), failureOf(context)) }
    }

    @Test
    fun `a complete advertising configuration wires the gate into the channel and the worker`() {
        val policy = AdvertisingConsentPolicy { _, _ -> AdvertisingConsent(send = false, night = false) }
        withPorts.withBean(AdvertisingConsentPolicy::class.java, { policy })
            .withPropertyValues("skeleton.notification.advertising.fallback-zone=Asia/Seoul", "skeleton.notification.advertising.sender-name=Example Co.")
            .run { context ->
                assertEquals(null, context.startupFailure)
                assertTrue(context.getBean(AdvertisingGate::class.java).isAdvertising("promo.tips"))
            }
    }

    @Test
    fun `no advertising types means no advertising checks at all`() {
        runner.withPropertyValues("skeleton.notification.push.enabled=true")
            .withBean(PushDeviceStore::class.java, { FakePushDeviceStore() })
            .withBean(PushTypeMuteStore::class.java, { FakePushTypeMuteStore() })
            .withBean(PushTasks::class.java, { RecordingPushTasks() })
            .run { context ->
                assertEquals(null, context.startupFailure)
                assertTrue(!context.getBean(AdvertisingGate::class.java).isAdvertising("promo.tips"))
            }
    }
}
