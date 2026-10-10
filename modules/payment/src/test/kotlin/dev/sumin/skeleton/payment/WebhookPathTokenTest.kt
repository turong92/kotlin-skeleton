package dev.sumin.skeleton.payment

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookPathTokenTest {
    private val good = "Good.Token_0123456789~abcdefXYZ"

    @Test
    fun `only the exact configured token matches - length, case and prefixes included`() {
        assertTrue(WebhookPathToken.matches(good, good))
        assertTrue(WebhookPathToken.matches("  $good  ", good), "the configured value is trimmed, the given one is not")
        listOf(null, "", good.lowercase(), good + "x", good.dropLast(1), good.drop(1), " $good").forEach { given ->
            assertFalse(WebhookPathToken.matches(good, given), "given=$given")
        }
    }

    @Test
    fun `a configured token that is empty or blank matches nothing`() {
        listOf("", "   ").forEach { configured -> listOf(null, "", " ", good).forEach { given -> assertFalse(WebhookPathToken.matches(configured, given)) } }
    }

    @Test
    fun `weak means short, unsafe in an address, or too few different characters - empty is not weak but empty`() {
        assertFalse(WebhookPathToken.isWeak(good))
        assertTrue(WebhookPathToken.isWeak("a1B2c3D4e5F6g7H8i9J0k1L"), "23 characters")
        assertTrue(WebhookPathToken.isWeak(good.replace('T', '/')), "a slash cannot be part of one address segment")
        assertTrue(WebhookPathToken.isWeak("ab".repeat(20)), "two different characters")
        assertTrue(WebhookPathToken.isWeak("abcdefg".repeat(5)), "seven different characters")
        assertFalse(WebhookPathToken.isWeak("abcdefgh".repeat(4)), "eight different characters")
    }
}
