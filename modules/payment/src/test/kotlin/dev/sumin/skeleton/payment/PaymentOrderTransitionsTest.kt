package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentOrderStatus.CANCELED
import dev.sumin.skeleton.payment.PaymentOrderStatus.CONFIRMING
import dev.sumin.skeleton.payment.PaymentOrderStatus.EXPIRED
import dev.sumin.skeleton.payment.PaymentOrderStatus.FAILED
import dev.sumin.skeleton.payment.PaymentOrderStatus.PAID
import dev.sumin.skeleton.payment.PaymentOrderStatus.PARTIALLY_REFUNDED
import dev.sumin.skeleton.payment.PaymentOrderStatus.PENDING
import dev.sumin.skeleton.payment.PaymentOrderStatus.REFUNDED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** 스펙 §5.2 의 전이표를 **그대로** 옮겨 적은 것 — 코드의 표를 읽어 만들지 않는다(같은 실수를 두 번 하지 않으려고). */
class PaymentOrderTransitionsTest {
    private val allowed: Set<Pair<PaymentOrderStatus, PaymentOrderStatus>> = setOf(
        PENDING to CONFIRMING, // 본인의 승인 요청
        PENDING to CANCELED, // 본인이 결제창에서 그만둠
        PENDING to EXPIRED, // 만료 작업
        PENDING to PAID, // 조회가 "받았다"
        CONFIRMING to PAID, // 승인 응답 · 조회
        CONFIRMING to FAILED, // 확정 거절 · 조회가 실패
        CONFIRMING to CONFIRMING, // 모름 — 상태를 바꾸지 않는다
        FAILED to PAID, // 결제사 조회 증거로만
        EXPIRED to PAID,
        CANCELED to PAID,
        PAID to PARTIALLY_REFUNDED, // 환불
        PAID to REFUNDED,
        PARTIALLY_REFUNDED to PARTIALLY_REFUNDED,
        PARTIALLY_REFUNDED to REFUNDED,
    )

    @TestFactory
    fun `every pair of statuses is allowed exactly when the table says so`(): List<DynamicTest> =
        PaymentOrderStatus.entries.flatMap { from ->
            PaymentOrderStatus.entries.map { to ->
                val expected = (from to to) in allowed
                DynamicTest.dynamicTest("$from -> $to is ${if (expected) "allowed" else "rejected"}") {
                    assertEquals(expected, PaymentOrderTransitions.isAllowed(from, to))
                    if (expected) {
                        PaymentOrderTransitions.require(from, to)
                    } else {
                        assertFailsWith<IllegalStateException> { PaymentOrderTransitions.require(from, to) }
                    }
                }
            }
        }

    @Test
    fun `nothing leaves a paid order for a failure or a close - a late failure signal cannot undo money`() {
        listOf(PAID, PARTIALLY_REFUNDED, REFUNDED).forEach { from ->
            listOf(FAILED, EXPIRED, CANCELED, PENDING, CONFIRMING).forEach { to ->
                assertTrue(!PaymentOrderTransitions.isAllowed(from, to), "$from -> $to")
            }
        }
    }

    @Test
    fun `the closed statuses and the paid family are named once`() {
        assertEquals(setOf(FAILED, EXPIRED, CANCELED), PaymentOrderStatus.entries.filter { it.isClosed }.toSet())
        assertEquals(setOf(PAID, PARTIALLY_REFUNDED, REFUNDED), PaymentOrderStatus.entries.filter { it.isPaidFamily }.toSet())
    }
}
