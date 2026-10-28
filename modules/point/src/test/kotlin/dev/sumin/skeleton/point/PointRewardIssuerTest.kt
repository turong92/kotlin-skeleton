package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardPermanentFailure
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.pointtest.FakeWallet
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PointRewardIssuerTest {
    private val now = Instant.parse("2026-10-28T00:00:00Z")
    private val wallet = FakeWallet()
    private val issuer = PointRewardIssuer(wallet, TimeProvider.fixed(now))

    private fun grant(id: String, vararg params: Pair<String, String>) = RewardGrantView(id, "a1", "point", params.toMap(), "attendance", "k-$id")

    @Test
    fun `the same grant issued twice earns once and answers with the same entry`() {
        val first = issuer.issue(grant("g1", "amount" to "100"))
        val second = issuer.issue(grant("g1", "amount" to "100"))

        assertEquals(first, second)
        assertEquals(1, wallet.entries.size)
        assertEquals("GRANT", wallet.entries.single().sourceType)
        assertEquals("g1", wallet.entries.single().sourceRef)
    }

    @Test
    fun `expiresInDays sets the bundle expiry and leaving it out means no expiry`() {
        issuer.issue(grant("g1", "amount" to "10", "expiresInDays" to "30"))
        issuer.issue(grant("g2", "amount" to "10"))

        assertEquals(now.plus(30, ChronoUnit.DAYS), wallet.entries[0].expiresAt)
        assertEquals(null, wallet.entries[1].expiresAt)
    }

    @Test
    fun `settings that can never work are rejected when saved and fail for good when issued`() {
        listOf(
            mapOf("amount" to "0"), mapOf("amount" to "-5"), mapOf("amount" to "abc"), emptyMap(),
            mapOf("amount" to "10", "expiresInDays" to "0"), mapOf("amount" to "10", "expiresInDays" to "x"),
        ).forEach { params ->
            assertFailsWith<IllegalArgumentException> { issuer.validate(params) }
            assertFailsWith<RewardPermanentFailure> { issuer.issue(RewardGrantView("g", "a1", "point", params, "attendance", "k")) }
        }
        issuer.validate(mapOf("amount" to "10", "expiresInDays" to "365"))
        assertEquals(0, wallet.entries.size)
    }

    @Test
    fun `the label reads in the caller language`() {
        assertEquals("포인트 100점", issuer.describe(mapOf("amount" to "100"), Locale.KOREAN).title)
        assertEquals("100 points", issuer.describe(mapOf("amount" to "100"), Locale.ENGLISH).title)
    }
}
