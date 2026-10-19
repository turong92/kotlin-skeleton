package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrderResponseEasyPayBrandTest {
    private fun brandOf(provider: String, method: String?) =
        OrderResponse.of(PaymentTestOrders.order(provider = provider).copy(method = method), 0, false, null).easyPayBrand

    @Test
    fun `an easy pay the provider took directly shows its own brand, with no provider name in the response`() {
        assertEquals("KAKAOPAY", brandOf("kakaopay", "EASY_PAY"))
        assertEquals("NAVERPAY", brandOf("naverpay", "EASY_PAY"))
    }

    @Test
    fun `any other provider or method has no brand - the screen keeps saying easy pay`() {
        assertNull(brandOf("toss", "EASY_PAY"))
        assertNull(brandOf("kakaopay", null))
        assertNull(brandOf("kakaopay", "CARD"))
    }
}
