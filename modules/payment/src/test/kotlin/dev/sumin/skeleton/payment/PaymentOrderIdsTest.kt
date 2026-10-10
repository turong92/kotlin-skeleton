package dev.sumin.skeleton.payment

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaymentOrderIdsTest {
    @Test
    fun `an order id is ord_ followed by 32 lowercase hex characters`() {
        val id = PaymentOrderIds.newOrderId(SecureRandom())

        assertTrue(Regex("ord_[0-9a-f]{32}").matches(id), id)
    }

    @Test
    fun `the dedupe key is the sha256 of account id, a newline and the key`() {
        // printf 'acc_1\nkey-1' | shasum -a 256
        assertEquals(
            "15dbf3857c4260ada112b97d46de40c2089ecc0fe7b436c74cc11f7acd582418",
            PaymentOrderIds.dedupeKey("acc_1", IdempotencyKey("key-1")),
        )
    }

    @Test
    fun `a key with a line break is refused - it could be shifted across the separator`() {
        assertFailsWith<IllegalArgumentException> { PaymentOrderIds.dedupeKey("a", IdempotencyKey("b\nc")) }
    }
}
