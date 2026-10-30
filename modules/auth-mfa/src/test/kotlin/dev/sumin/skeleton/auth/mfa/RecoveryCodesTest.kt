package dev.sumin.skeleton.auth.mfa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RecoveryCodesTest {
    @Test
    fun `ten codes of 16 base32 characters in four groups, all different`() {
        val codes = RecoveryCodes.generate()
        assertEquals(10, codes.size)
        assertEquals(10, codes.toSet().size)
        codes.forEach { assertTrue(Regex("^([A-Z2-7]{4}-){3}[A-Z2-7]{4}$").matches(it), it) }
    }

    @Test
    fun `typing it without dashes, in lower case or with spaces finds the same hash`() {
        val code = "ABCD-EFGH-2345-67AB"
        val hash = RecoveryCodes.hash(code)
        assertEquals(64, hash.length)
        assertEquals(hash, RecoveryCodes.hash("abcdefgh234567ab"))
        assertEquals(hash, RecoveryCodes.hash(" abcd efgh-2345-67ab "))
        assertNotEquals(hash, RecoveryCodes.hash("ABCD-EFGH-2345-67AC"))
    }
}
