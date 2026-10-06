package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConsentServiceTest {
    private val ctx = ConsentContext("203.0.113.9", "Mozilla/5.0 test")
    private val me = Subject.account("acc_1")

    private fun claim(type: String, version: String, locale: String? = "ko") = ConsentClaim(type, version, locale)

    private fun ConsentFixture.agreeCurrent(subject: Subject = me) =
        service.record(subject, listOf(claim("terms", "v2"), claim("privacy", "v1")), ctx, "sign-up")

    @Test
    fun `a subject that agreed to nothing is blocked on the required types only`() {
        val f = ConsentFixture()

        val status = f.service.status(me)

        assertTrue(status.blocked)
        assertEquals(listOf(MissingConsent("terms", "v2", MissingReason.NOT_AGREED), MissingConsent("privacy", "v1", MissingReason.NOT_AGREED)), status.missing)
        assertEquals(setOf("terms", "privacy", "marketing"), status.items.map { it.type }.toSet())
        assertTrue(status.items.all { it.state == ConsentState.MISSING })
        assertEquals(status.missing, f.service.requireCurrent(me))
        assertEquals(listOf(MissingConsent("marketing", "v1", MissingReason.NOT_AGREED)), f.service.requireCurrent(me, listOf("marketing")), "an optional type is reported when it is asked for")
    }

    @Test
    fun `recording stores what was shown - version, hash of the source, locale, time, source, ip and user agent`() {
        val f = ConsentFixture()

        val status = f.agreeCurrent()

        assertTrue(!status.blocked)
        assertEquals(2, f.store.rows.size)
        val terms = f.store.rows.single { it.type == "terms" }
        assertEquals("v2", terms.version)
        assertEquals(f.hash("terms", "v2"), terms.sha256)
        assertEquals("ko", terms.locale)
        assertEquals(f.clock.now, terms.at)
        assertEquals("sign-up", terms.source)
        assertEquals("203.0.113.9", terms.ip)
        assertEquals("Mozilla/5.0 test", terms.userAgent)
        assertEquals(ConsentAction.AGREED, terms.action)
        assertEquals(1, terms.seq)
        assertEquals(ConsentState.CURRENT, status.items.single { it.type == "terms" }.state)
    }

    @Test
    fun `the locale that was shown is the one stored, with the hash of that locale, and an unavailable locale falls back to the default`() {
        val f = ConsentFixture()
        f.service.record(me, listOf(claim("terms", "v2", "en"), claim("privacy", "v1", "fr")), ctx, "consent")

        val terms = f.store.rows.single { it.type == "terms" }
        assertEquals("en", terms.locale)
        assertEquals(f.hash("terms", "v2", "en"), terms.sha256)
        val privacy = f.store.rows.single { it.type == "privacy" }
        assertEquals("ko", privacy.locale)
        assertEquals(f.hash("privacy", "v1", "ko"), privacy.sha256)
    }

    @Test
    fun `ip and user agent are stored only when the options say so, and a very long user agent is cut`() {
        val off = ConsentFixture(options = ConsentOptions(storeIp = false, storeUserAgent = false))
        off.agreeCurrent()
        assertTrue(off.store.rows.all { it.ip == null && it.userAgent == null })

        val long = ConsentFixture()
        long.service.record(me, listOf(claim("terms", "v2")), ConsentContext("1.2.3.4", "x".repeat(1000)), "consent")
        assertEquals(255, long.store.rows.single().userAgent!!.length)
    }

    @Test
    fun `recording the same agreement again is a no-op, so a double click or a retry leaves one row`() {
        val f = ConsentFixture()
        f.agreeCurrent()
        val calls = f.store.appendCalls

        f.agreeCurrent()

        assertEquals(2, f.store.rows.size)
        assertEquals(calls, f.store.appendCalls, "nothing was even attempted")
    }

    @Test
    fun `sixteen concurrent identical records leave exactly one row`() {
        val f = ConsentFixture()
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..16).map { pool.submit { go.await(); f.service.record(me, listOf(claim("terms", "v2")), ctx, "consent") } }
        go.countDown()
        results.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, f.store.rows.size)
    }

    @Test
    fun `a stale version is refused with the version to agree to, and nothing of the request is recorded`() {
        val f = ConsentFixture()

        val e = assertFailsWith<LegalException> { f.service.record(me, listOf(claim("privacy", "v1"), claim("terms", "v1")), ctx, "consent") }

        assertEquals(LegalErrorCode.VERSION_STALE, e.errorCode)
        assertEquals(mapOf("stale" to listOf(StaleVersion("terms", "v2"))), e.data)
        assertEquals(0, f.store.rows.size, "all or nothing")
    }

    @Test
    fun `a version that does not take effect yet cannot be agreed to`() {
        val f = ConsentFixture()
        val e = assertFailsWith<LegalException> { f.service.record(me, listOf(claim("terms", "v3")), ctx, "consent") }
        assertEquals(LegalErrorCode.VERSION_STALE, e.errorCode)
    }

    @Test
    fun `an unknown type is refused and named`() {
        val f = ConsentFixture()
        val e = assertFailsWith<LegalException> { f.service.record(me, listOf(claim("nope", "v1")), ctx, "consent") }
        assertEquals(LegalErrorCode.UNKNOWN_DOCUMENT, e.errorCode)
        assertEquals(mapOf("types" to listOf("nope")), e.data)
    }

    @Test
    fun `inside the grace window the previous version still counts and can still be agreed to, after it the subject is outdated`() {
        val f = ConsentFixture(grace = Duration.ofDays(30))
        f.clock.now = f.v2Start.minusSeconds(60)   // v1 is still the current one
        f.service.record(me, listOf(claim("terms", "v1"), claim("privacy", "v1")), ctx, "sign-up")
        assertTrue(!f.service.status(me).blocked)

        f.clock.now = f.v2Start.plus(Duration.ofDays(29))
        val graced = f.service.status(me)
        assertTrue(!graced.blocked, "grace: not blocked yet")
        val item = graced.items.single { it.type == "terms" }
        assertEquals(ConsentState.GRACE, item.state)
        assertEquals(f.v2Start.plus(Duration.ofDays(30)), item.graceUntil)
        assertEquals(emptyList(), f.service.requireCurrent(me))
        f.service.record(me, listOf(claim("terms", "v1", "ko")), ctx, "consent")   // a page opened before the deploy still works
        assertEquals("v1", f.store.rows.filter { it.type == "terms" }.maxBy { it.seq }.version)

        f.clock.now = f.v2Start.plus(Duration.ofDays(30))
        val over = f.service.status(me)
        assertEquals(ConsentState.OUTDATED, over.items.single { it.type == "terms" }.state)
        assertEquals(listOf(MissingConsent("terms", "v2", MissingReason.STALE)), over.missing)
        assertEquals(LegalErrorCode.VERSION_STALE, assertFailsWith<LegalException> { f.service.record(me, listOf(claim("terms", "v1")), ctx, "consent") }.errorCode)
    }

    @Test
    fun `agreeing to the new version after it took effect makes the subject current again`() {
        val f = ConsentFixture()
        f.clock.now = f.v2Start.minusSeconds(1)
        f.service.record(me, listOf(claim("terms", "v1"), claim("privacy", "v1")), ctx, "sign-up")
        f.clock.now = f.v2Start.plusSeconds(1)
        assertEquals(listOf("terms"), f.service.status(me).missing.map { it.type })

        f.service.record(me, listOf(claim("terms", "v2")), ctx, "re-consent")

        assertTrue(!f.service.status(me).blocked)
        assertEquals(listOf(1, 2), f.store.rows.filter { it.type == "terms" }.map { it.seq })
    }

    @Test
    fun `withdrawing an optional agreement records its own event with the version and hash that were withdrawn`() {
        val f = ConsentFixture()
        f.service.record(me, listOf(claim("marketing", "v1", "en")), ctx, "sign-up")
        f.clock.now = f.clock.now.plusSeconds(3600)

        val status = f.service.withdraw(me, "marketing", ConsentContext("198.51.100.2", "ua2"))

        val withdrawn = f.store.rows.last()
        assertEquals(ConsentAction.WITHDRAWN, withdrawn.action)
        assertEquals("v1", withdrawn.version)
        assertEquals(f.hash("marketing", "v1", "en"), withdrawn.sha256)
        assertEquals("en", withdrawn.locale)
        assertEquals("withdraw", withdrawn.source)
        assertEquals(2, withdrawn.seq)
        assertEquals(f.clock.now, withdrawn.at)
        assertEquals(ConsentState.WITHDRAWN, status.items.single { it.type == "marketing" }.state)
        assertEquals(2, f.store.rows.size, "the agreement is kept, not edited")
    }

    @Test
    fun `withdrawing twice, or something never agreed to, changes nothing`() {
        val f = ConsentFixture()
        f.service.withdraw(me, "marketing", ctx)
        assertEquals(0, f.store.rows.size)
        f.service.record(me, listOf(claim("marketing", "v1")), ctx, "settings")
        f.service.withdraw(me, "marketing", ctx)
        f.service.withdraw(me, "marketing", ctx)
        assertEquals(2, f.store.rows.size)
    }

    @Test
    fun `agreeing again after a withdrawal is a new agreement event`() {
        val f = ConsentFixture()
        f.service.record(me, listOf(claim("marketing", "v1")), ctx, "settings")
        f.service.withdraw(me, "marketing", ctx)
        val status = f.service.record(me, listOf(claim("marketing", "v1")), ctx, "settings")

        assertEquals(listOf(ConsentAction.AGREED, ConsentAction.WITHDRAWN, ConsentAction.AGREED), f.store.rows.map { it.action })
        assertEquals(ConsentState.CURRENT, status.items.single { it.type == "marketing" }.state)
    }

    @Test
    fun `a required document cannot be withdrawn and an unknown one is named`() {
        val f = ConsentFixture()
        f.agreeCurrent()
        assertEquals(LegalErrorCode.WITHDRAWAL_NOT_ALLOWED, assertFailsWith<LegalException> { f.service.withdraw(me, "terms", ctx) }.errorCode)
        assertEquals(LegalErrorCode.UNKNOWN_DOCUMENT, assertFailsWith<LegalException> { f.service.withdraw(me, "nope", ctx) }.errorCode)
    }

    @Test
    fun `a consent can be tied to a reference such as an order and is independent of the general one`() {
        val f = ConsentFixture(required = setOf("terms", "privacy", "marketing"))
        f.agreeCurrent()

        assertEquals(listOf("marketing"), f.service.requireCurrent(me, listOf("marketing"), "order-7").map { it.type })

        f.service.record(me, listOf(claim("marketing", "v1")), ctx, "checkout", referenceId = "order-7")

        assertEquals(emptyList(), f.service.requireCurrent(me, listOf("marketing"), "order-7"))
        assertEquals(listOf("marketing"), f.service.requireCurrent(me, listOf("marketing")).map { it.type }, "the general slot is untouched")
        assertEquals("order-7", f.store.rows.last().referenceId)
    }

    @Test
    fun `subjects are independent, also across subject types with the same id`() {
        val f = ConsentFixture()
        f.agreeCurrent(Subject.account("same"))
        assertTrue(f.service.status(Subject("device", "same")).blocked)
        assertTrue(!f.service.status(Subject.account("same")).blocked)
    }

    @Test
    fun `too many events by one subject in a day are refused, retries that change nothing do not count`() {
        val f = ConsentFixture(options = ConsentOptions(maxEventsPerDay = 3))
        f.service.record(me, listOf(claim("terms", "v2"), claim("privacy", "v1")), ctx, "consent")
        repeat(5) { f.service.record(me, listOf(claim("terms", "v2")), ctx, "consent") }   // no-ops
        f.service.record(me, listOf(claim("marketing", "v1")), ctx, "consent")

        val e = assertFailsWith<LegalException> { f.service.withdraw(me, "marketing", ctx) }

        assertEquals(LegalErrorCode.RATE_LIMITED, e.errorCode)
        f.clock.now = f.clock.now.plus(Duration.ofHours(25))
        f.service.withdraw(me, "marketing", ctx)
        assertEquals(ConsentAction.WITHDRAWN, f.store.rows.last().action)
    }

    @Test
    fun `a status item for a type with no version in force yet is not listed`() {
        val f = ConsentFixture()
        f.clock.now = java.time.Instant.parse("2025-06-01T00:00:00Z")
        assertEquals(emptyList(), f.service.status(me).items)
        assertEquals(emptyList(), f.service.status(me).missing)
    }
}
