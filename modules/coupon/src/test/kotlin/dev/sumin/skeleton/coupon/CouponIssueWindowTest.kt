package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardPermanentFailure
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** 발행 기간 `[issueStartsAt, issueEndsAt)` 밖에서는 운영자 발급 · 코드 등록 · 보상 지급이 모두 409 `COUPON.ISSUE_CLOSED` - 시작은 포함, 끝은 제외 */
class CouponIssueWindowTest {
    private val store = FakeCouponStore()
    private val time = MutableTime()
    private val definitions = CouponDefinitions(store, time)
    private val issuance = CouponIssuance(store, time)
    private val codes = CouponCodes(store, issuance, time)
    private val issuer = CouponRewardIssuer(definitions, issuance)

    private val opens = NOW.plus(Duration.ofDays(1))
    private val closes = NOW.plus(Duration.ofDays(2))

    private fun windowed(): CouponDefinition {
        val d = definition(issueStartsAt = opens, issueEndsAt = closes, perAccountLimit = 5)
        store.definitions[d.id] = d
        return d
    }

    private fun assertClosed(block: () -> Unit) {
        val e = assertFailsWith<CouponException> { block() }
        assertEquals("COUPON.ISSUE_CLOSED", e.errorCode.code)
        assertEquals(409, e.status.value())
    }

    @Test
    fun `issuing before the start and at or after the end is closed, and nothing is written`() {
        val d = windowed()
        assertClosed { issuance.issue(d.id, "acc_1", CouponSource.ADMIN, "r1") }
        time.current = opens.minusMillis(1)
        assertClosed { issuance.issue(d.id, "acc_1", CouponSource.ADMIN, "r1") }
        time.current = closes
        assertClosed { issuance.issue(d.id, "acc_1", CouponSource.ADMIN, "r1") }
        time.current = closes.plusSeconds(60)
        assertClosed { issuance.issue(d.id, "acc_1", CouponSource.ADMIN, "r1") }
        assertEquals(0, store.coupons.size)
        assertEquals(0, store.definitions.getValue(d.id).issuedCount)
    }

    @Test
    fun `the start instant is inside and the last instant before the end is inside`() {
        val d = windowed()
        time.current = opens
        assertNotNull(issuance.issue(d.id, "acc_1", CouponSource.ADMIN, "r1"))
        time.current = closes.minusMillis(1)
        assertNotNull(issuance.issue(d.id, "acc_1", CouponSource.ADMIN, "r2"))
        assertEquals(2, store.definitions.getValue(d.id).issuedCount)
    }

    @Test
    fun `an open ended window only has the side it was given`() {
        store.definitions["only_start"] = definition(id = "only_start", issueStartsAt = opens)
        store.definitions["only_end"] = definition(id = "only_end", issueEndsAt = closes)
        assertClosed { issuance.issue("only_start", "acc_1", CouponSource.ADMIN, "a") }
        assertNotNull(issuance.issue("only_end", "acc_1", CouponSource.ADMIN, "b"))
        time.current = closes
        assertClosed { issuance.issue("only_end", "acc_2", CouponSource.ADMIN, "c") }
        time.current = opens.plusSeconds(5 * 86_400)
        assertNotNull(issuance.issue("only_start", "acc_1", CouponSource.ADMIN, "d"))
    }

    @Test
    fun `an operator batch, a shared code and a reward grant are all refused outside the window`() {
        val d = windowed()
        assertClosed { issuance.issueByAdmin(d.id, listOf("acc_1", "acc_2")) }
        assertEquals(0, store.coupons.size)

        val code = codes.create(d.id, "OPENING")
        assertClosed { codes.redeem("acc_1", code) }
        assertEquals(0, store.codes.getValue(code).redeemedCount)
        assertEquals(false, store.hasRedeemed(code, "acc_1"))

        val grant = RewardGrantView("grant_1", "acc_1", "coupon", mapOf("definitionId" to d.id), "attendance", "k")
        assertFailsWith<RewardPermanentFailure> { issuer.issue(grant) }

        time.current = opens
        codes.redeem("acc_1", code)
        issuer.issue(grant)
        assertEquals(2, store.coupons.size)
    }

    @Test
    fun `a window must run forward and a use start must come before the use end`() {
        fun create(start: java.time.Instant?, end: java.time.Instant?, from: java.time.Instant?, until: java.time.Instant?) = definitions.create(
            CouponDefinitionInput(name = "w", stages = listOf(CouponStageInput("FREE", periods = 1)), validUntil = until, issueStartsAt = start, issueEndsAt = end, validFrom = from),
        )
        listOf(
            { create(closes, opens, null, null) },
            { create(opens, opens, null, null) },
            { create(null, null, closes, opens) },
            { create(null, null, opens, opens) },
        ).forEach { block ->
            assertEquals("COUPON.DEFINITION_INVALID", assertFailsWith<CouponException> { block() }.errorCode.code)
        }
        assertEquals(0, store.definitions.size)

        val ok = create(opens, closes, opens, closes)
        assertEquals(Triple(opens, closes, opens), Triple(ok.issueStartsAt, ok.issueEndsAt, ok.validFrom))
        val plain = create(null, null, null, null)
        assertEquals(Triple(null, null, null), Triple(plain.issueStartsAt, plain.issueEndsAt, plain.validFrom))
    }
}
