package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 컨텍스트 하나를 시험 여럿이 나눠 쓰는 쪽(견본 앱의 응원 통합 시험)이 시험 사이에 대본을 비운다 — 한 시험이 남긴 대본이 다음 시험의 첫 호출을 가로채지 않게. */
class ScriptedGatewayClearScriptsTest {
    private val won = { n: Long -> PaymentAmount(n, "KRW") }

    @Test
    fun `clearScripts drops the leftover confirm, refund and lookup-failure scripts - the next calls behave as with a fresh gateway`() {
        val gateway = ScriptedGateway()
        gateway.authenticate("pay_1", "ord_1", won(3000))
        gateway.script(ConfirmScript.Decline(), ConfirmScript.ServerError)
        gateway.scriptRefund(RefundScript.Decline(), RefundScript.ServerError)
        gateway.failLookups(5)

        gateway.clearScripts()

        assertEquals(PaymentOperationStatus.CONFIRMED, gateway.confirm(PaymentConfirmRequest("pay_1", won(3000))).status, "no confirm script is left")
        assertNotNull(gateway.lookup(PaymentLookupQuery("pay_1", null)), "no lookup failure is left")
        assertEquals(PaymentOperationStatus.REFUNDED, gateway.refund(PaymentRefundRequest("pay_1", won(1000), reason = "test", idempotencyKey = IdempotencyKey("k-1"))).status, "no refund script is left")
    }
}
