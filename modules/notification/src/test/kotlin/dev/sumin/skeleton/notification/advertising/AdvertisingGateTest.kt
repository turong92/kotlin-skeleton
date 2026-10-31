package dev.sumin.skeleton.notification.advertising

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvertisingGateTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val clock = FixedClock(Instant.parse("2026-10-31T00:00:00Z"))
    private val consenting = AdvertisingConsent(send = true, night = false)
    private val nightOk = AdvertisingConsent(send = true, night = true)

    private fun props(
        adTypes: List<String> = listOf("promo.tips"),
        fallbackZone: String? = "Asia/Seoul",
        senderName: String? = "Example Co.",
        nightStart: String = "21:00",
        nightEnd: String = "08:00",
        nightConsentType: String = "marketing-night",
        titlePrefix: String = "(광고)",
        bodySuffix: String = "",
    ) = AdvertisingProperties(
        types = adTypes, fallbackZone = fallbackZone, senderName = senderName, nightStart = nightStart, nightEnd = nightEnd,
        nightConsentType = nightConsentType, titlePrefix = titlePrefix, bodySuffix = bodySuffix,
    )

    private fun gate(
        policy: AdvertisingConsentPolicy? = StubPolicy(mapOf("acc_a" to consenting)),
        zones: AdvertisingRecipientZones? = null,
        properties: AdvertisingProperties = props(),
    ) = AdvertisingGate(properties, policy, zones, clock)

    /** 서울 현지 시각 [hhmm] (2026-10-31) 로 시계를 맞춘다 */
    private fun at(hour: Int, minute: Int, date: String = "2026-10-31") {
        clock.now = java.time.LocalDate.parse(date).atTime(hour, minute).atZone(seoul).toInstant()
    }

    private fun seoulAt(date: String, hour: Int, minute: Int = 0): Instant = java.time.LocalDate.parse(date).atTime(hour, minute).atZone(seoul).toInstant()

    @Test
    fun `a recipient without consent, or one the policy answers send=false for, is skipped - also when the night flag is set`() {
        at(12, 0)
        val g = gate(StubPolicy(mapOf("withdrawn" to AdvertisingConsent(send = false, night = false), "night-only" to AdvertisingConsent(send = false, night = true))))

        assertEquals(AdvertisingDecision.Skip, g.decide("unknown"))
        assertEquals(AdvertisingDecision.Skip, g.decide("withdrawn"))
        assertEquals(AdvertisingDecision.Skip, g.decide("night-only"), "the night consent only counts together with the advertising consent")
    }

    @Test
    fun `a failing consent lookup sends nothing`() {
        at(12, 0)
        val policy = StubPolicy(mapOf("acc_a" to consenting)).apply { failure = IllegalStateException("db down") }

        assertEquals(AdvertisingDecision.Skip, gate(policy).decide("acc_a"))
    }

    @Test
    fun `the night window is 21 00 inclusive to 08 00 exclusive in the recipient's zone`() {
        val g = gate()

        at(20, 59); assertEquals(AdvertisingDecision.SendNow, g.decide("acc_a"))
        at(21, 0); assertEquals(AdvertisingDecision.Hold(seoulAt("2026-11-01", 8)), g.decide("acc_a"))
        at(23, 30); assertEquals(AdvertisingDecision.Hold(seoulAt("2026-11-01", 8)), g.decide("acc_a"), "before midnight: tomorrow 08:00")
        at(0, 30, "2026-11-01"); assertEquals(AdvertisingDecision.Hold(seoulAt("2026-11-01", 8)), g.decide("acc_a"), "after midnight: today 08:00")
        at(7, 59, "2026-11-01"); assertEquals(AdvertisingDecision.Hold(seoulAt("2026-11-01", 8)), g.decide("acc_a"))
        at(8, 0, "2026-11-01"); assertEquals(AdvertisingDecision.SendNow, g.decide("acc_a"))
    }

    @Test
    fun `with the night consent the night is sent at once`() {
        val g = gate(StubPolicy(mapOf("acc_a" to nightOk)))

        at(23, 0)

        assertEquals(AdvertisingDecision.SendNow, g.decide("acc_a"))
    }

    @Test
    fun `the hold ends at 08 00 local time even when the zone changes its clock in between`() {
        val newYork = ZoneId.of("America/New_York")
        val g = gate(zones = StubZones(mapOf("acc_a" to newYork)))
        clock.now = java.time.LocalDate.parse("2026-03-07").atTime(22, 0).atZone(newYork).toInstant()   // 이 밤 02:00 에 서머타임이 시작된다

        val hold = g.decide("acc_a") as AdvertisingDecision.Hold

        assertEquals(java.time.LocalDate.parse("2026-03-08").atTime(8, 0).atZone(newYork).toInstant(), hold.runAt)
        assertEquals(Instant.parse("2026-03-08T12:00:00Z"), hold.runAt)   // EDT(-4) 라서 12:00Z, 나흘 전 EST(-5)였다면 13:00Z
    }

    @Test
    fun `the zone comes from the app first, then from fallback-zone, and a failing zone lookup sends nothing`() {
        clock.now = Instant.parse("2026-10-31T13:00:00Z")   // 서울 22:00 = 도쿄 22:00 = 뉴욕 09:00
        val policy = StubPolicy(mapOf("acc_a" to consenting, "acc_b" to consenting, "acc_c" to consenting))
        val zones = StubZones(mapOf("acc_a" to ZoneId.of("America/New_York"), "acc_b" to null))
        val g = gate(policy, zones)

        assertEquals(AdvertisingDecision.SendNow, g.decide("acc_a"), "the app's zone: New York is mid-morning")
        assertTrue(g.decide("acc_b") is AdvertisingDecision.Hold, "no zone from the app: fallback Seoul is 22:00")
        assertEquals(seoul, g.zoneOf("acc_b"))
        assertEquals(AdvertisingDecision.Skip, gate(policy, StubZones(failure = IllegalStateException("profile down"))).decide("acc_c"))
    }

    @Test
    fun `the title gets the advertising mark exactly once and the body gets the suffix inside the size limit`() {
        val g = gate(properties = props(bodySuffix = "수신 거부: 설정 > 알림"))

        val branded = g.brand("새 기능", "가".repeat(2000), 3000)
        val again = g.brand(branded.title, branded.body, 3000)

        assertEquals("(광고) 새 기능", branded.title)
        assertEquals("(광고) 새 기능", again.title)
        assertEquals(branded.body, again.body)   // 처리기가 다시 붙여도 본문 끝 문구가 겹치지 않는다
        assertTrue(branded.body!!.endsWith("수신 거부: 설정 > 알림"))
        assertTrue(branded.body!!.toByteArray().size <= 3000)
        assertEquals("(광고) 이미 붙음", g.brand("(광고) 이미 붙음", "x", 3000).title)
    }

    @Test
    fun `an advertising configuration is complete or the problems are named`() {
        val ok = gate().problems()
        assertEquals(emptyList(), ok)

        assertTrue(gate(policy = null).problems().any { "notification-legal" in it })
        assertTrue(gate(properties = props(fallbackZone = null, senderName = "x")).problems().any { "fallback-zone" in it })
        assertTrue(gate(properties = props(senderName = null)).problems().any { "sender-name" in it })
        assertTrue(gate(properties = props(fallbackZone = "Mars/Base", senderName = "x")).problems().any { "fallback-zone" in it })
        assertTrue(gate(properties = props(nightStart = "9pm")).problems().any { "night-start" in it })
        assertTrue(gate(properties = props(nightStart = "21:00", nightEnd = "21:00")).problems().any { "night-start" in it && "night-end" in it })
        assertTrue(gate(properties = props(nightConsentType = "marketing")).problems().any { "consent" in it })
        assertTrue(gate(properties = props(adTypes = listOf("advertising.consent.changed"))).problems().any { "advertising.consent" in it })
        assertTrue(gate(properties = props(titlePrefix = " ")).problems().any { "title-prefix" in it })
    }

    @Test
    fun `without advertising types the gate is off and has nothing to complain about`() {
        val g = gate(policy = null, properties = AdvertisingProperties())

        assertEquals(emptyList(), g.problems())
        assertTrue(!g.isAdvertising("promo.tips"))
    }
}
