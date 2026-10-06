package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignUpGateTest {
    private val ctx = ConsentContext("203.0.113.9", "ua")

    private fun gate(f: ConsentFixture) = LegalSignUpGate(f.service, f.catalog, f.rules, f.clock)

    private fun missing(f: ConsentFixture, vararg claims: ConsentClaim): List<MissingConsent> {
        val e = assertFailsWith<LegalException> { gate(f).check(claims.toList()) }
        assertEquals(LegalErrorCode.CONSENT_REQUIRED, e.errorCode)
        @Suppress("UNCHECKED_CAST")
        return (e.data as Map<String, List<MissingConsent>>)["missing"]!!
    }

    @Test
    fun `every required-at-sign-up document with its current version lets the request through, optional ones too`() {
        val f = ConsentFixture()
        gate(f).check(listOf(ConsentClaim("terms", "v2"), ConsentClaim("privacy", "v1"), ConsentClaim("marketing", "v1")))
    }

    @Test
    fun `a required document that is not claimed is named as not agreed`() {
        val f = ConsentFixture()
        assertEquals(listOf(MissingConsent("privacy", "v1", MissingReason.NOT_AGREED)), missing(f, ConsentClaim("terms", "v2")))
        assertEquals(
            listOf(MissingConsent("terms", "v2", MissingReason.NOT_AGREED), MissingConsent("privacy", "v1", MissingReason.NOT_AGREED)),
            missing(f),
        )
    }

    @Test
    fun `a claim for an old version is stale and says which version to show`() {
        val f = ConsentFixture()
        assertEquals(listOf(MissingConsent("terms", "v2", MissingReason.STALE)), missing(f, ConsentClaim("terms", "v1"), ConsentClaim("privacy", "v1")))
    }

    @Test
    fun `an optional document with a stale version is refused too, so a recorded consent is always true`() {
        val f = ConsentFixture(requiredAtSignUp = setOf("privacy"))
        assertEquals(listOf(MissingConsent("terms", "v2", MissingReason.STALE)), missing(f, ConsentClaim("privacy", "v1"), ConsentClaim("terms", "v1")))
    }

    @Test
    fun `an unknown type is reported as unknown without a version`() {
        val f = ConsentFixture()
        assertEquals(
            listOf(MissingConsent("nope", null, MissingReason.UNKNOWN)),
            missing(f, ConsentClaim("terms", "v2"), ConsentClaim("privacy", "v1"), ConsentClaim("nope", "x")),
        )
    }

    @Test
    fun `the previous version is accepted inside the grace window`() {
        val f = ConsentFixture(grace = Duration.ofDays(30))
        gate(f).check(listOf(ConsentClaim("terms", "v1"), ConsentClaim("privacy", "v1")))
        f.clock.now = f.v2Start.plus(Duration.ofDays(31))
        assertEquals(listOf(MissingConsent("terms", "v2", MissingReason.STALE)), missing(f, ConsentClaim("terms", "v1"), ConsentClaim("privacy", "v1")))
    }

    @Test
    fun `a required type whose first version is not in force yet cannot be demanded`() {
        val f = ConsentFixture()
        f.clock.now = java.time.Instant.parse("2025-06-01T00:00:00Z")
        gate(f).check(emptyList())
    }

    @Test
    fun `the check looks at the request only, so it never touches the consent store`() {
        val f = ConsentFixture()
        gate(f).check(listOf(ConsentClaim("terms", "v2"), ConsentClaim("privacy", "v1")))
        assertEquals(0, f.store.appendCalls)
        assertEquals(0, f.store.rows.size)
    }

    @Test
    fun `recording writes what the sign-up showed, as a sign-up event of the new account`() {
        val f = ConsentFixture()
        f.clock.now = f.v2Start.minusSeconds(5)   // the page showed v1 ...
        val claims = listOf(ConsentClaim("terms", "v1", "en"), ConsentClaim("privacy", "v1"))
        gate(f).check(claims)
        f.clock.now = f.v2Start.plusSeconds(120)   // ... and v2 took effect before the code was entered

        gate(f).record("acc_9", claims, ctx)

        val terms = f.store.rows.single { it.type == "terms" }
        assertEquals(Subject.account("acc_9"), terms.subject)
        assertEquals("v1", terms.version, "the version the user was shown, not the current one")
        assertEquals(f.hash("terms", "v1", "en"), terms.sha256)
        assertEquals("sign-up", terms.source)
        assertEquals("203.0.113.9", terms.ip)
        assertEquals(f.clock.now, terms.at)
        assertEquals(2, f.store.rows.size)
    }

    @Test
    fun `recording twice for the same account leaves one row per document`() {
        val f = ConsentFixture()
        val claims = listOf(ConsentClaim("terms", "v2"), ConsentClaim("privacy", "v1"))
        gate(f).record("acc_9", claims, ctx)
        gate(f).record("acc_9", claims, ctx)
        assertEquals(2, f.store.rows.size)
    }

    @Test
    fun `recording a version that does not exist stops the transaction`() {
        val f = ConsentFixture()
        assertFailsWith<IllegalStateException> { gate(f).record("acc_9", listOf(ConsentClaim("terms", "zzz")), ctx) }
        assertTrue(f.store.rows.isEmpty())
    }
}
