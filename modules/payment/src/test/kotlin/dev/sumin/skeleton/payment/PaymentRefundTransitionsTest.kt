package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentRefundStatus.FAILED
import dev.sumin.skeleton.payment.PaymentRefundStatus.PENDING
import dev.sumin.skeleton.payment.PaymentRefundStatus.REJECTED
import dev.sumin.skeleton.payment.PaymentRefundStatus.REQUESTED
import dev.sumin.skeleton.payment.PaymentRefundStatus.REVERSED
import dev.sumin.skeleton.payment.PaymentRefundStatus.SUCCEEDED
import dev.sumin.skeleton.payment.PaymentRefundStatus.WITHDRAWN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** 스펙 §5.3 의 환불 전이표를 **그대로** 옮겨 적은 것 — 코드의 표를 읽어 만들지 않는다. */
class PaymentRefundTransitionsTest {
    private val allowed: Set<Pair<PaymentRefundStatus, PaymentRefundStatus>> = setOf(
        REQUESTED to PENDING, // 운영자가 요청을 승인
        REQUESTED to REJECTED, // 운영자가 거절
        REQUESTED to WITHDRAWN, // 본인이 거둠
        PENDING to SUCCEEDED, // 결제사가 환불됐다고 했다(응답 또는 조회)
        PENDING to FAILED, // 확정 거절 · 조회해 보니 적용되지 않았다
        PENDING to PENDING, // 모름
        SUCCEEDED to REVERSED, // 차지백 분쟁에서 이겨 돈이 돌아왔다(결제사 증거로만 - 이 줄을 부르는 곳은 한 곳뿐이다)
    )

    @TestFactory
    fun `every pair of refund statuses is allowed exactly when the table says so`(): List<DynamicTest> =
        PaymentRefundStatus.entries.flatMap { from ->
            PaymentRefundStatus.entries.map { to ->
                val expected = (from to to) in allowed
                DynamicTest.dynamicTest("$from -> $to is ${if (expected) "allowed" else "rejected"}") {
                    assertEquals(expected, PaymentRefundTransitions.isAllowed(from, to))
                    if (expected) PaymentRefundTransitions.require(from, to) else assertFailsWith<IllegalStateException> { PaymentRefundTransitions.require(from, to) }
                }
            }
        }

    @Test
    fun `only a requested or pending line is open - it is the one that holds the order's single open slot`() {
        assertEquals(setOf(REQUESTED, PENDING), PaymentRefundStatus.entries.filter { PaymentRefundTransitions.isOpen(it) }.toSet())
        assertEquals("ord_x", PaymentRefundTransitions.openKeyFor(PENDING, "ord_x"))
        assertEquals("ord_x", PaymentRefundTransitions.openKeyFor(REQUESTED, "ord_x"))
        listOf(SUCCEEDED, FAILED, REJECTED, WITHDRAWN, REVERSED).forEach { assertEquals(null, PaymentRefundTransitions.openKeyFor(it, "ord_x"), "$it") }
    }
}
