package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.adswebtest.FakeWebRewardStore
import dev.sumin.skeleton.adswebtest.MutableClock
import dev.sumin.skeleton.adswebtest.RecordingIssuer
import dev.sumin.skeleton.common.reward.RewardGrantView
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 돈이 걸린 경로 — 1회용 · 최소 경과 · 만료 · 상한 · 토큰. 저장소는 메모리 가짜(실 DB 시험은 `ads-web-rewarded-jdbc`) */
class WebRewardServiceTest {
    private val clock = MutableClock()
    private val store = FakeWebRewardStore()
    private val issuer = RecordingIssuer()

    private fun service(properties: WebRewardedProperties = WebRewardedProperties(rewardType = "point", rewardParams = mapOf("amount" to "1")), withIssuer: Boolean = true) =
        WebRewardService(store, properties, if (withIssuer) issuer else null, clock)

    private fun code(block: () -> Unit) = assertFailsWith<WebRewardedException> { block() }.errorCode

    @Test
    fun `a grant before min-elapsed is TOO_EARLY and keeps the session open - 7 seconds later the same session pays`() {
        val s = service()
        val session = s.open("a1", "unlock", "ip1")

        clock.advance(Duration.ofSeconds(6))
        assertEquals(WebRewardedErrorCode.TOO_EARLY, code { s.grant("a1", session.id) })
        assertEquals(0, issuer.issued.size)

        clock.advance(Duration.ofSeconds(1))
        assertNotNull(s.grant("a1", session.id).session.grantedAt)
        assertEquals(1, issuer.issued.size)
    }

    @Test
    fun `a grant after the session expired is SESSION_EXPIRED and pays nothing`() {
        val s = service()
        val session = s.open("a1", "unlock", "ip1")
        clock.advance(Duration.ofMinutes(15))

        assertEquals(WebRewardedErrorCode.SESSION_EXPIRED, code { s.grant("a1", session.id) })
        assertEquals(0, issuer.issued.size)
    }

    @Test
    fun `a second grant of the same session is SESSION_USED and pays once`() {
        val s = service()
        val session = s.open("a1", "unlock", "ip1")
        clock.advance(Duration.ofSeconds(8))
        s.grant("a1", session.id)

        assertEquals(WebRewardedErrorCode.SESSION_USED, code { s.grant("a1", session.id) })
        assertEquals(1, issuer.issued.size)
    }

    @Test
    fun `another account's session is not found and changes nothing`() {
        val s = service()
        val session = s.open("a1", "unlock", "ip1")
        clock.advance(Duration.ofSeconds(8))

        assertEquals(WebRewardedErrorCode.SESSION_NOT_FOUND, code { s.grant("a2", session.id) })
        assertEquals(0, issuer.issued.size)
        assertNotNull(s.grant("a1", session.id))
    }

    @Test
    fun `at the account limit opening a session is DAILY_LIMIT before any ad is shown`() {
        val s = service(WebRewardedProperties(rewardType = "point", rewardParams = mapOf("amount" to "1"), accountDailyLimit = 1))
        val first = s.open("a1", "unlock", "ip1")
        clock.advance(Duration.ofSeconds(8))
        s.grant("a1", first.id)

        assertEquals(WebRewardedErrorCode.DAILY_LIMIT, code { s.open("a1", "unlock", "ip1") })
    }

    @Test
    fun `the limit is also checked at grant - two sessions opened before the limit cannot both pay`() {
        val s = service(WebRewardedProperties(rewardType = "point", rewardParams = mapOf("amount" to "1"), accountDailyLimit = 1))
        val one = s.open("a1", "unlock", "ip1")
        val two = s.open("a1", "unlock", "ip1")
        clock.advance(Duration.ofSeconds(8))
        s.grant("a1", one.id)

        assertEquals(WebRewardedErrorCode.DAILY_LIMIT, code { s.grant("a1", two.id) })
        assertEquals(1, issuer.issued.size)
    }

    @Test
    fun `a rejected grant leaves no half-counted limit - the ip limit refusing also undoes the account count`() {
        val s = service(WebRewardedProperties(rewardType = "point", rewardParams = mapOf("amount" to "1"), accountDailyLimit = 5, ipDailyLimit = 1))
        val a = s.open("a1", "unlock", "ip1")
        val b = s.open("a2", "unlock", "ip1")
        clock.advance(Duration.ofSeconds(8))
        s.grant("a1", a.id)

        assertEquals(WebRewardedErrorCode.DAILY_LIMIT, code { s.grant("a2", b.id) })
        assertEquals(0, store.grantedOn(WebRewardScope.ACCOUNT, "a2", dayStart()))
        assertEquals(1, store.grantedOn(WebRewardScope.IP, "ip1", dayStart()))
    }

    @Test
    fun `the ip limit counts across accounts`() {
        val s = service(WebRewardedProperties(rewardType = "point", rewardParams = mapOf("amount" to "1"), ipDailyLimit = 1))
        val a = s.open("a1", "unlock", "ip1")
        clock.advance(Duration.ofSeconds(8))
        s.grant("a1", a.id)

        assertEquals(WebRewardedErrorCode.DAILY_LIMIT, code { s.open("a2", "unlock", "ip1") })
        assertNotNull(s.open("a2", "unlock", "ip2"))
    }

    @Test
    fun `point mode calls the issuer once with the session as the key`() {
        val s = service()
        val session = s.open("a1", "unlock", "ip1")
        clock.advance(Duration.ofSeconds(8))

        val granted = s.grant("a1", session.id)

        assertEquals(listOf(RewardGrantView(session.id, "a1", "point", mapOf("amount" to "1"), "ADS_WEB_REWARDED", session.id)), issuer.issued)
        assertNull(granted.token)
        assertEquals("pt_1", granted.session.rewardRef)
    }

    @Test
    fun `an issuer failure is REWARD_UNAVAILABLE and leaves the session open and the counter untouched - then the same session pays`() {
        val s = service(WebRewardedProperties(rewardType = "point", rewardParams = mapOf("amount" to "1"), accountDailyLimit = 1))
        val session = s.open("a1", "unlock", "ip1")
        clock.advance(Duration.ofSeconds(8))
        issuer.failure = IllegalStateException("db went away")

        assertEquals(WebRewardedErrorCode.REWARD_UNAVAILABLE, code { s.grant("a1", session.id) })
        assertNull(store.sessions.getValue(session.id).grantedAt)
        assertEquals(0, store.grantedOn(WebRewardScope.ACCOUNT, "a1", dayStart()))

        issuer.failure = null
        assertNotNull(s.grant("a1", session.id).session.grantedAt)
    }

    @Test
    fun `token mode hands the token out once and redeems it once - other account or purpose never`() {
        val s = service(WebRewardedProperties())
        val session = s.open("a1", "unlock", "ip1")
        clock.advance(Duration.ofSeconds(8))
        val token = assertNotNull(s.grant("a1", session.id).token)

        assertNull(s.redeem(token, "a2", "unlock"), "다른 계정")
        assertNull(s.redeem(token, "a1", "other"), "다른 목적")
        assertNotNull(s.redeem(token, "a1", "unlock"))
        assertNull(s.redeem(token, "a1", "unlock"), "한 번만")
        assertTrue(store.tokens.keys.none { it == token }, "저장은 해시뿐이다")
    }

    @Test
    fun `an expired token is refused`() {
        val s = service(WebRewardedProperties())
        val session = s.open("a1", "unlock", "ip1")
        clock.advance(Duration.ofSeconds(8))
        val token = assertNotNull(s.grant("a1", session.id).token)

        clock.advance(Duration.ofMinutes(10))
        assertNull(s.redeem(token, "a1", "unlock"))
    }

    @Test
    fun `a reward type without its issuer or with bad params cannot start`() {
        assertFailsWith<IllegalStateException> { service(WebRewardedProperties(rewardType = "point"), withIssuer = false) }
        assertFailsWith<IllegalArgumentException> { service(WebRewardedProperties(rewardType = "point", rewardParams = emptyMap())) }
    }

    @Test
    fun `settings that would let a reward through too early are refused`() {
        assertFailsWith<IllegalArgumentException> { WebRewardedProperties(minElapsed = Duration.ofMinutes(20)) }
        assertFailsWith<IllegalArgumentException> { WebRewardedProperties(minElapsed = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { WebRewardedProperties(accountDailyLimit = -1) }
    }

    private fun dayStart() = java.time.Instant.parse("2026-11-18T00:00:00Z")
}
