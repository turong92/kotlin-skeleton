package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrderResponseEasyPayBrandTest {
    private fun responseOf(brand: String?) =
        OrderResponse.of(PaymentTestOrders.order(provider = "toss").copy(method = "EASY_PAY", easyPayBrand = brand), 0, false, null)

    @Test
    fun `the brand on the ledger row goes out as a neutral code, next to no provider name`() {
        assertEquals("KAKAOPAY", responseOf("KAKAOPAY").easyPayBrand)
    }

    @Test
    fun `an old row has no brand`() {
        assertNull(responseOf(null).easyPayBrand)
    }
}
