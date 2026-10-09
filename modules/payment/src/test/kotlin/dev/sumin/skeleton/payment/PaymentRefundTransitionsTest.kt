package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentRefundStatus.FAILED
import dev.sumin.skeleton.payment.PaymentRefundStatus.PENDING
import dev.sumin.skeleton.payment.PaymentRefundStatus.REJECTED
import dev.sumin.skeleton.payment.PaymentRefundStatus.REQUESTED
import dev.sumin.skeleton.payment.PaymentRefundStatus.SUCCEEDED
import dev.sumin.skeleton.payment.PaymentRefundStatus.WITHDRAWN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    fun `a line can be created only as requested, pending (an operator refund) or succeeded (a cancel found at the provider)`() {
        PaymentRefundStatus.entries.forEach { status ->
            val expected = status in setOf(REQUESTED, PENDING, SUCCEEDED)
            assertEquals(expected, PaymentRefundTransitions.isCreatable(status), "$status")
            if (expected) PaymentRefundTransitions.requireCreatable(status) else assertFailsWith<IllegalStateException> { PaymentRefundTransitions.requireCreatable(status) }
        }
    }

    @Test
    fun `only a requested or pending line is open - it is the one that holds the order's single open slot`() {
        assertEquals(setOf(REQUESTED, PENDING), PaymentRefundStatus.entries.filter { PaymentRefundTransitions.isOpen(it) }.toSet())
        assertEquals("ord_x", PaymentRefundTransitions.openKeyFor(PENDING, "ord_x"))
        assertEquals("ord_x", PaymentRefundTransitions.openKeyFor(REQUESTED, "ord_x"))
        listOf(SUCCEEDED, FAILED, REJECTED, WITHDRAWN).forEach { assertEquals(null, PaymentRefundTransitions.openKeyFor(it, "ord_x"), "$it") }
    }

    @Test
    fun `a closed line never opens again`() {
        listOf(SUCCEEDED, FAILED, REJECTED, WITHDRAWN).forEach { from ->
            PaymentRefundStatus.entries.forEach { to -> assertFalse(PaymentRefundTransitions.isAllowed(from, to), "$from -> $to") }
        }
    }

    @Test
    fun `reason codes - people may pick three, an operator four, and the provider console one is ours alone`() {
        assertEquals(
            setOf(PaymentRefundReason.CUSTOMER_REQUEST, PaymentRefundReason.DUPLICATE, PaymentRefundReason.NOT_DELIVERED),
            PaymentRefundReason.entries.filter { it.forCustomer }.toSet(),
        )
        assertEquals(
            setOf(PaymentRefundReason.CUSTOMER_REQUEST, PaymentRefundReason.DUPLICATE, PaymentRefundReason.NOT_DELIVERED, PaymentRefundReason.OPERATOR),
            PaymentRefundReason.entries.filter { it.forOperator }.toSet(),
        )
        assertEquals(PaymentRefundReason.DUPLICATE, PaymentRefundReason.parse("DUPLICATE"))
        assertEquals(null, PaymentRefundReason.parse("duplicate"), "the code is exact")
        assertEquals(null, PaymentRefundReason.parse("because I said so"))
    }

    @Test
    fun `the text sent to the provider is a fixed sentence of ours, short enough for the provider and never a customer's words`() {
        PaymentRefundReason.entries.forEach { reason ->
            assertTrue(reason.providerText.isNotBlank() && reason.providerText.length <= 200, "$reason")
            assertTrue(Regex("[A-Za-z ]+").matches(reason.providerText), "$reason: ${reason.providerText}")
        }
    }
}
