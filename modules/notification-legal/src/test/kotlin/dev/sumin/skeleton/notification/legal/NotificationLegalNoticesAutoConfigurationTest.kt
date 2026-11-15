package dev.sumin.skeleton.notification.legal

import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import dev.sumin.skeleton.common.contact.ContactAddresses
import dev.sumin.skeleton.common.wake.OnRequestTask
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
import dev.sumin.skeleton.notification.advertising.AdvertisingNoticeStore
import dev.sumin.skeleton.notification.advertising.AdvertisingNotificationTypes
import dev.sumin.skeleton.notification.mail.MailMessage
import dev.sumin.skeleton.notification.mail.MailSendResult
import dev.sumin.skeleton.notification.mail.MailSender
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 조립 - **푸시 모듈 없이** 광고를 켜면 정책 · 통지 · 재확인 스캔이 서고, 광고를 안 켜면 이 모듈은 아무 빈도 없다 */
class NotificationLegalNoticesAutoConfigurationTest {
    private val base = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, LegalAutoConfiguration::class.java, NotificationLegalAutoConfiguration::class.java))
        .withBean(ConsentStore::class.java, { MemoryConsentStore() })
        .withBean(LegalLedger::class.java, { MemoryLedger() })
        .withBean(AdvertisingNoticeStore::class.java, { MemoryNoticeStore() })
        .withPropertyValues("skeleton.legal.acknowledge-template=true", "skeleton.job-queue.mode=on-request")

    private val ads = arrayOf(
        "skeleton.notification.advertising.types=promo.tips",
        "skeleton.notification.advertising.fallback-zone=Asia/Seoul",
        "skeleton.notification.advertising.sender-name=Example Co.",
        "skeleton.notification.advertising.body-suffix=Opt out: Settings > Notifications",
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
    fun `with advertising types and no push module the policy, the notice hook and the scan are wired`() {
        base.withPropertyValues(*ads).run { context ->
            assertNull(context.startupFailure, failureOf(context))
            assertTrue(context.getBean(AdvertisingConsentPolicy::class.java) is LegalAdvertisingConsentPolicy)
            assertEquals(1, context.getBeansOfType(OnRequestTask::class.java).values.count { it.name == "advertising-reconfirm" })
            assertEquals(1, context.getBeansOfType(AdvertisingReconfirmScanner::class.java).size)
            assertEquals(1, context.getBeansOfType(AdvertisingConsentNotifier::class.java).size)
        }
    }

    @Test
    fun `an agreement through the legal service reaches the broker as one informational notice without any push module`() {
        base.withPropertyValues(*ads).run { context ->
            val heard = CopyOnWriteArrayList<NotificationEvent>()
            context.getBean(NotificationSubscriptionRegistry::class.java).subscribe(subscriber = NotificationSubscriber { heard += it })
            val version = context.getBean(LegalCatalog::class.java).current("marketing", Instant.now())!!.version

            context.getBean(ConsentService::class.java).record(Subject.account("acc_a"), listOf(ConsentClaim("marketing", version, "ko")), ConsentContext("203.0.113.9", "ua"), "settings")

            assertEquals(listOf(AdvertisingNotificationTypes.CONSENT_CHANGED), heard.map { it.type })
            assertEquals(setOf("acc_a"), heard.single().recipientIds)
        }
    }

    @Test
    fun `advertising types without the legal module fail to start naming the module to add`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, NotificationLegalAutoConfiguration::class.java))
            .withPropertyValues(*ads)
            .run { context -> assertTrue("AdvertisingConsentPolicy" in failureOf(context), failureOf(context)) }
    }

    @Test
    fun `advertising types without a notice store fail to start instead of silently skipping the two-year reminders`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, LegalAutoConfiguration::class.java, NotificationLegalAutoConfiguration::class.java))
            .withBean(ConsentStore::class.java, { MemoryConsentStore() })
            .withBean(LegalLedger::class.java, { MemoryLedger() })
            .withPropertyValues("skeleton.legal.acknowledge-template=true", "skeleton.job-queue.mode=on-request", *ads)
            .run { context -> assertTrue("AdvertisingNoticeStore" in failureOf(context), failureOf(context)) }
    }

    @Test
    fun `without a scheduler or the request-driven mode the reconfirm scan has no way to run and startup fails`() {
        base.withPropertyValues(*ads, "skeleton.job-queue.mode=local").run { context -> assertTrue("scheduler" in failureOf(context), failureOf(context)) }
    }

    @Test
    fun `without push the consent notice is mailed to the contact address of the person`() {
        val mails = CopyOnWriteArrayList<MailMessage>()
        base.withPropertyValues(*ads)
            .withBean(MailSender::class.java, { MailSender { mails += it; MailSendResult(accepted = true) } })
            .withBean(ContactAddresses::class.java, { ContactAddresses { id -> if (id == "acc_a") "a@example.com" else null } })
            .withConfiguration(AutoConfigurations.of(NotificationLegalMailAutoConfiguration::class.java))
            .run { context ->
                assertNull(context.startupFailure, failureOf(context))
                val version = context.getBean(LegalCatalog::class.java).current("marketing", Instant.now())!!.version
                val service = context.getBean(ConsentService::class.java)

                service.record(Subject.account("acc_a"), listOf(ConsentClaim("marketing", version, "ko")), ConsentContext("203.0.113.9", "ua"), "settings")
                service.record(Subject.account("acc_nomail"), listOf(ConsentClaim("marketing", version, "ko")), ConsentContext("203.0.113.9", "ua"), "settings")

                assertEquals(listOf("a@example.com"), mails.flatMap { it.to })
                assertEquals("광고성 정보 수신 동의 처리 결과", mails.single().subject)
                assertTrue("Example Co." in mails.single().text)
            }
    }
}
