package dev.sumin.skeleton.payment.toss

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TossPaymentPropertiesToStringTest {
    @Test
    fun `toString never prints the secret key - it says only whether one is set`() {
        val set = TossPaymentProperties(enabled = true, secretKey = "test_sk_planted_secret_value", clientKey = "test_ck_public")
        val unset = TossPaymentProperties()

        assertFalse(set.toString().contains("test_sk_planted_secret_value"), set.toString())
        assertTrue(set.toString().contains("<redacted>"), set.toString())
        assertTrue(unset.toString().contains("<unset>"), unset.toString())
        assertTrue(set.toString().contains("enabled=true"), "the rest of the settings stay readable")
    }

}
