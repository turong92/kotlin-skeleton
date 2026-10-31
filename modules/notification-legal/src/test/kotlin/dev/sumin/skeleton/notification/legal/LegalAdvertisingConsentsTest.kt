package dev.sumin.skeleton.notification.legal

import dev.sumin.skeleton.notification.advertising.AdvertisingConsent
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 광고 동의가 「지금 보내도 되나」로 바뀌는 규칙 - 미동의 · 철회 · 2년 만료 · 새 판(OUTDATED) · 야간은 광고 동의 위에서만 */
class LegalAdvertisingConsentsTest {
    private val kit = LegalKit()
    private val advertising = AdvertisingProperties(fallbackZone = "Asia/Seoul", senderName = "Example Co.")
    private val consents = LegalAdvertisingConsents(kit.service, advertising)
    private val policy = LegalAdvertisingConsentPolicy(consents)
    private val t0 = kit.clock.now
    private val twoYears = Duration.ofDays(730)

    private fun snapshot() = consents.snapshot("acc_a", kit.clock.now)
    private fun decision() = policy.consentOf("acc_a", kit.clock.now)

    @Test
    fun `nobody who never agreed can be sent to`() {
        val s = snapshot()

        assertEquals(AdvertisingConsentState.NONE, s.consent.state)
        assertEquals(AdvertisingConsentState.NONE, s.night.state)
        assertEquals(AdvertisingConsent(send = false, night = false), decision())
        assertNull(s.consent.agreedAt)
    }

    @Test
    fun `an agreement is valid for two years from the latest agreement and not a second longer`() {
        kit.agree("acc_a")

        assertEquals(AdvertisingConsentState.AGREED, snapshot().consent.state)
        assertEquals(t0, snapshot().consent.agreedAt)
        assertEquals(t0.plus(twoYears), snapshot().consent.expiresAt)
        assertEquals(AdvertisingConsent(send = true, night = false), decision())

        kit.clock.now = t0.plus(twoYears).minusSeconds(1)
        assertEquals(AdvertisingConsent(send = true, night = false), decision())

        kit.clock.now = t0.plus(twoYears)
        assertEquals(AdvertisingConsentState.EXPIRED, snapshot().consent.state)
        assertEquals(AdvertisingConsent(send = false, night = false), decision(), "an expired agreement counts as none")
    }

    @Test
    fun `a withdrawal stops advertising and the night consent with it, without any second write`() {
        kit.agree("acc_a")
        kit.agree("acc_a", "marketing-night")
        assertEquals(AdvertisingConsent(send = true, night = true), decision())

        kit.withdraw("acc_a")

        assertEquals(AdvertisingConsentState.WITHDRAWN, snapshot().consent.state)
        assertEquals(AdvertisingConsentState.AGREED, snapshot().night.state)
        assertEquals(false, snapshot().night.effective, "the night consent alone sends nothing")
        assertEquals(AdvertisingConsent(send = false, night = false), decision())
    }

    @Test
    fun `the night consent alone, or a withdrawn night consent, never allows advertising`() {
        kit.agree("acc_a", "marketing-night")
        assertEquals(AdvertisingConsent(send = false, night = false), decision())

        kit.agree("acc_a")
        assertEquals(AdvertisingConsent(send = true, night = true), decision())
        kit.withdraw("acc_a", "marketing-night")
        assertEquals(AdvertisingConsent(send = true, night = false), decision())
    }

    @Test
    fun `a reconfirm starts the two years again`() {
        kit.agree("acc_a")
        kit.clock.now = t0.plus(Duration.ofDays(700))
        kit.agree("acc_a", source = "reconfirm")

        assertEquals(kit.clock.now.plus(twoYears), snapshot().consent.expiresAt)
        kit.clock.now = t0.plus(twoYears).plusSeconds(1)
        assertEquals(AdvertisingConsentState.AGREED, snapshot().consent.state)
    }

    @Test
    fun `an agreement to an older version than the one in force is outdated and sends nothing`() {
        kit.agree("acc_a")
        kit.clock.now = Instant.parse("2035-01-02T00:00:00Z")   // marketing v2 시행 뒤 (2년도 이미 지났지만 새 판이 먼저 보인다)

        val s = snapshot()

        assertEquals(AdvertisingConsentState.OUTDATED, s.consent.state)
        assertNull(s.consent.expiresAt)
        assertEquals(AdvertisingConsent(send = false, night = false), decision())
    }

    @Test
    fun `the reconfirm notice is due from thirty days before the end`() {
        kit.agree("acc_a")

        kit.clock.now = t0.plus(twoYears).minus(Duration.ofDays(30)).minusSeconds(1)
        assertEquals(false, snapshot().consent.reconfirmDue)
        kit.clock.now = t0.plus(twoYears).minus(Duration.ofDays(30))
        assertEquals(true, snapshot().consent.reconfirmDue)
        kit.clock.now = t0.plus(twoYears)
        assertEquals(false, snapshot().consent.reconfirmDue, "once expired it is not 'due' any more - it is expired")
    }

    @Test
    fun `an agreement is not mistaken for someone else's`() {
        kit.agree("acc_b")

        assertEquals(AdvertisingConsent(send = false, night = false), decision())
    }

    @Test
    fun `a night document the app never published means no night consent`() {
        val noNight = LegalAdvertisingConsents(kit.service, advertising.copy(nightConsentType = "marketing-night-missing"))
        kit.agree("acc_a")

        assertEquals(AdvertisingConsentState.NONE, noNight.snapshot("acc_a", kit.clock.now).night.state)
        assertEquals(AdvertisingConsent(send = true, night = false), LegalAdvertisingConsentPolicy(noNight).consentOf("acc_a", kit.clock.now))
    }
}
