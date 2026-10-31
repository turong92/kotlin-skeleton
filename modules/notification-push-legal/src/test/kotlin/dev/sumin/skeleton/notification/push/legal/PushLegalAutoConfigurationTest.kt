package dev.sumin.skeleton.notification.push.legal

import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import dev.sumin.skeleton.legal.ConsentService
import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.LegalAutoConfiguration
import dev.sumin.skeleton.legal.LegalCatalog
import dev.sumin.skeleton.legal.LegalLedger
import dev.sumin.skeleton.legal.Subject
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import dev.sumin.skeleton.notification.advertising.AdvertisingConsentPolicy
import dev.sumin.skeleton.notification.advertising.AdvertisingNotificationTypes
import dev.sumin.skeleton.notification.legal.LegalAdvertisingConsentPolicy
import dev.sumin.skeleton.notification.legal.NotificationLegalAutoConfiguration
import dev.sumin.skeleton.notification.push.PushAdvertisingNoticeStore
import dev.sumin.skeleton.notification.push.PushAutoConfiguration
import dev.sumin.skeleton.notification.push.PushDeviceStore
import dev.sumin.skeleton.notification.push.PushTasks
import dev.sumin.skeleton.notification.push.PushTypeMuteStore
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 조립: 광고를 켜면 정책 · 통지 · 스캔이 서고 코어의 시작 검사를 통과한다 / 광고를 안 켜면 이 모듈은 아무 빈도 없다 */
class PushLegalAutoConfigurationTest {
    private inline fun <reified T : Any> stub(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T

    private val base = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, LegalAutoConfiguration::class.java, PushAutoConfiguration::class.java, NotificationLegalAutoConfiguration::class.java, PushLegalAutoConfiguration::class.java))
        .withBean(ConsentStore::class.java, { MemoryConsentStore() })
        .withBean(LegalLedger::class.java, { MemoryLedger() })
        .withBean(PushDeviceStore::class.java, { stub<PushDeviceStore>() })
        .withBean(PushTypeMuteStore::class.java, { stub<PushTypeMuteStore>() })
        .withBean(PushTasks::class.java, { stub<PushTasks>() })
        .withBean(PushAdvertisingNoticeStore::class.java, { MemoryNoticeStore() })
        .withPropertyValues("skeleton.notification.push.enabled=true", "skeleton.legal.acknowledge-template=true")

    private val ads = arrayOf(
        "skeleton.notification.advertising.types=promo.tips",
        "skeleton.notification.advertising.fallback-zone=Asia/Seoul",
        "skeleton.notification.advertising.sender-name=Example Co.",
    )

    private fun failureOf(context: AssertableApplicationContext) =
        generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `without advertising types this module adds nothing`() {
        base.run { context ->
            assertNull(context.startupFailure)
            assertEquals(0, context.getBeansOfType(AdvertisingConsentPolicy::class.java).size)
            assertEquals(0, context.getBeansOfType(AdvertisingReconfirmScanner::class.java).size)
            assertEquals(0, context.getBeansOfType(AdvertisingConsentNotifier::class.java).size)
        }
    }

    @Test
    fun `with advertising types the policy, the notice hook and the scan are wired and the core start-up check passes`() {
        base.withPropertyValues(*ads).run { context ->
            assertNull(context.startupFailure, failureOf(context))
            assertTrue(context.getBean(AdvertisingConsentPolicy::class.java) is LegalAdvertisingConsentPolicy)
            assertEquals(1, context.getBeansOfType(AdvertisingReconfirmScanner::class.java).size)
            assertEquals(1, context.getBeansOfType(AdvertisingConsentNotifier::class.java).size)
        }
    }

    @Test
    fun `an agreement through the legal service reaches the broker as one informational notice`() {
        base.withPropertyValues(*ads).run { context ->
            val heard = CopyOnWriteArrayList<NotificationEvent>()
            context.getBean(NotificationSubscriptionRegistry::class.java).subscribe(subscriber = NotificationSubscriber { heard += it })
            val version = context.getBean(LegalCatalog::class.java).current("marketing", Instant.now())!!.version

            context.getBean(ConsentService::class.java).record(Subject.account("acc_a"), listOf(ConsentClaim("marketing", version, "ko")), ConsentContext("203.0.113.9", "ua"), "push-settings")

            assertEquals(listOf(AdvertisingNotificationTypes.CONSENT_CHANGED), heard.map { it.type })
            assertEquals(setOf("acc_a"), heard.single().recipientIds)
        }
    }

    @Test
    fun `advertising types without the legal module fail to start naming the module to add`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, PushAutoConfiguration::class.java, NotificationLegalAutoConfiguration::class.java, PushLegalAutoConfiguration::class.java))
            .withBean(PushDeviceStore::class.java, { stub<PushDeviceStore>() })
            .withBean(PushTypeMuteStore::class.java, { stub<PushTypeMuteStore>() })
            .withBean(PushTasks::class.java, { stub<PushTasks>() })
            .withPropertyValues("skeleton.notification.push.enabled=true", *ads)
            .run { context -> assertTrue("AdvertisingConsentPolicy" in failureOf(context), failureOf(context)) }
    }
}
