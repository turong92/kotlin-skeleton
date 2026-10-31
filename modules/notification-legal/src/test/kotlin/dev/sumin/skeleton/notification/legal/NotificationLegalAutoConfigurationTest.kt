package dev.sumin.skeleton.notification.legal

import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.LegalAutoConfiguration
import dev.sumin.skeleton.legal.LegalLedger
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.advertising.AdvertisingConsentPolicy
import dev.sumin.skeleton.notification.advertising.AdvertisingDecision
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 푸시 모듈 없이도 광고 게이트(채널 무관)가 legal 동의로 판정한다 - 메일 · 슬랙 채널이 부를 수 있는 모양 */
class NotificationLegalAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, LegalAutoConfiguration::class.java, NotificationLegalAutoConfiguration::class.java))
        .withBean(ConsentStore::class.java, { MemoryConsentStore() })
        .withBean(LegalLedger::class.java, { MemoryLedger() })
        .withPropertyValues("skeleton.legal.acknowledge-template=true")

    @Test
    fun `without advertising types there is no policy bean, with them the gate decides from the legal consent records`() {
        runner.run { context ->
            assertNull(context.startupFailure)
            assertEquals(0, context.getBeansOfType(AdvertisingConsentPolicy::class.java).size)
        }
        runner.withPropertyValues("skeleton.notification.advertising.types=promo.tips", "skeleton.notification.advertising.fallback-zone=Asia/Seoul", "skeleton.notification.advertising.sender-name=Example Co.")
            .run { context ->
                assertNull(context.startupFailure, context.startupFailure?.toString())
                assertTrue(context.getBean(AdvertisingConsentPolicy::class.java) is LegalAdvertisingConsentPolicy)
                assertEquals(AdvertisingDecision.Skip, context.getBean(AdvertisingGate::class.java).decide("acc_without_consent"))
            }
    }
}
