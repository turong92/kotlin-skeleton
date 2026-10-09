package dev.sumin.skeleton.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginIdRulesTest {
    private fun problem(raw: String, min: Int = 4, max: Int = 20) = LoginIds.problemOf(LoginIds.normalize(raw), min, max)

    @Test
    fun `normalize trims the ends and lower-cases with a locale-free rule`() {
        assertEquals("sumin.kim", LoginIds.normalize("  Sumin.KIM \n"))
        assertEquals("ii", LoginIds.normalize("II"), "no Turkish dotless i")
    }

    @Test
    fun `full-width letters are refused rather than folded`() {
        assertEquals(LoginIdProblem.PATTERN, problem("ＡＢＣＤ"))
    }

    @Test
    fun `letters digits and the three symbols are accepted inside`() {
        listOf("abcd", "abc1", "ab.cd", "ab-cd", "ab_cd", "a.b-c_d", "sumin.kim", "user123").forEach { assertNull(problem(it), it) }
    }

    @Test
    fun `an upper case input is accepted and compared in lower case`() {
        assertNull(problem("SuMin.Kim"))
        assertEquals("sumin.kim", LoginIds.normalize("SuMin.Kim"))
    }

    @Test
    fun `a digit may start the id as long as a letter shows up somewhere`() {
        assertNull(problem("1abc"))
        assertNull(problem("12345a"))
        assertNull(problem("1234.a"))
    }

    @Test
    fun `an id made only of digits and symbols is refused as a pattern problem`() {
        listOf("1234", "123456", "12.34", "12-34", "12_34", "1.2.3.4").forEach { assertEquals(LoginIdProblem.PATTERN, problem(it), it) }
    }

    @Test
    fun `a symbol at the very start or the very end is refused`() {
        listOf(".abcd", "-abcd", "_abcd", "abcd.", "abcd-", "abcd_", ".abcd.", "_abcd_").forEach { assertEquals(LoginIdProblem.PATTERN, problem(it), it) }
    }

    @Test
    fun `two symbols in a row are refused in every combination`() {
        val symbols = listOf(".", "-", "_")
        for (a in symbols) for (b in symbols) assertEquals(LoginIdProblem.PATTERN, problem("ab$a${b}cd"), "ab$a${b}cd")
    }

    @Test
    fun `an at sign a space and other characters are refused`() {
        listOf("ab@cd", "ab cd", "ab+cd", "ab/cd", "한글아이디", "abcd😀", "ab\tcd").forEach { assertEquals(LoginIdProblem.PATTERN, problem(it), it) }
    }

    @Test
    fun `an empty input is a required problem and blanks count as empty`() {
        assertEquals(LoginIdProblem.REQUIRED, problem(""))
        assertEquals(LoginIdProblem.REQUIRED, problem("   "))
    }

    @Test
    fun `length is checked against the configured bounds - 4 and 20 by default`() {
        assertEquals(LoginIdProblem.SIZE, problem("abc"))
        assertNull(problem("abcd"))
        assertNull(problem("a".repeat(20)))
        assertEquals(LoginIdProblem.SIZE, problem("a".repeat(21)))
        assertNull(problem("abc", min = 3))
        assertEquals(LoginIdProblem.SIZE, problem("a".repeat(11), max = 10))
    }

    @Test
    fun `the length problem shows before the shape problem only when the shape itself is fine for a longer id`() {
        assertEquals(LoginIdProblem.SIZE, problem("a.b"), "valid shape, too short")
        assertEquals(LoginIdProblem.PATTERN, problem(".b"), "a broken shape is a pattern problem however short it is")
    }

    @Test
    fun `an input far longer than the column is a SIZE problem at once - the shape is never matched against it (I1)`() {
        val huge = "a" + ".a".repeat(50_000)
        assertEquals(LoginIdProblem.SIZE, problem(huge), "100KB of dotted letters must not recurse")
        assertEquals(LoginIdProblem.SIZE, problem("a".repeat(31), max = 30))
        assertEquals(LoginIdProblem.SIZE, problem("!".repeat(100_000)), "a wrong shape that is also huge reports the length")
    }

    @Test
    fun `the longest id the column holds is still judged by its shape`() {
        assertNull(problem("a".repeat(LoginIds.COLUMN_LENGTH), max = LoginIds.COLUMN_LENGTH))
        assertEquals(LoginIdProblem.PATTERN, problem("a." + "a".repeat(27) + ".", max = LoginIds.COLUMN_LENGTH))
    }

    @Test
    fun `clip cuts a long text to the column length without splitting a surrogate pair (I1)`() {
        assertEquals("abc", LoginIds.clip("abc"))
        assertEquals(LoginIds.COLUMN_LENGTH, LoginIds.clip("a".repeat(500)).length)
        val clipped = LoginIds.clip("a".repeat(LoginIds.COLUMN_LENGTH - 1) + "😀😀")
        assertTrue(clipped.length <= LoginIds.COLUMN_LENGTH && !clipped.last().isHighSurrogate(), clipped)
    }

    @Test
    fun `the Kelvin sign and other non-ASCII look-alikes are not folded into ASCII letters (M9)`() {
        assertEquals(LoginIdProblem.PATTERN, problem("\u212Aevin"), "KELVIN SIGN lower-cases to k with a full Unicode fold")
        assertEquals(LoginIdProblem.PATTERN, problem("\u0130stanbul"), "dotted capital I")
        assertEquals("\u212Aevin", LoginIds.normalize("\u212Aevin"))
        assertEquals("kevin", LoginIds.normalize("KEVIN"))
    }

    @Test
    fun `reserved words are compared without symbols and without case`() {
        val reserved = listOf("admin", "root")
        assertTrue(LoginIds.isReserved(LoginIds.normalize("admin"), reserved))
        assertTrue(LoginIds.isReserved(LoginIds.normalize("Ad.Min"), reserved))
        assertTrue(LoginIds.isReserved(LoginIds.normalize("ad_min"), reserved))
        assertTrue(LoginIds.isReserved(LoginIds.normalize("a-d.m_i-n"), reserved))
        assertTrue(LoginIds.isReserved(LoginIds.normalize("root"), reserved))
        assertFalse(LoginIds.isReserved(LoginIds.normalize("admin1"), reserved), "only the whole word, not a prefix")
        assertFalse(LoginIds.isReserved(LoginIds.normalize("sumin"), reserved))
    }

    @Test
    fun `a reserved entry written with symbols or capitals matches the same way`() {
        assertTrue(LoginIds.isReserved("admin", listOf("Ad.Min")))
        assertFalse(LoginIds.isReserved("admin", emptyList()))
    }

    @Test
    fun `at sign is how a sign-in field tells an email from an id`() {
        assertTrue(LoginIds.looksLikeEmail("a@b.c"))
        assertFalse(LoginIds.looksLikeEmail("sumin.kim"))
    }

    @Test
    fun `the three login id error codes and the two event kinds exist with the contract values`() {
        assertEquals("ACCOUNT.LOGIN_ID_TAKEN" to 409, AccountErrorCode.LOGIN_ID_TAKEN.let { it.code to it.status.value() })
        assertEquals("ACCOUNT.LOGIN_ID_REQUIRED" to 403, AccountErrorCode.LOGIN_ID_REQUIRED.let { it.code to it.status.value() })
        assertEquals("ACCOUNT.LOGIN_ID_ALREADY_SET" to 409, AccountErrorCode.LOGIN_ID_ALREADY_SET.let { it.code to it.status.value() })
        assertEquals(listOf("LOGIN_ID_SET", "LOGIN_ID_REMINDER_SENT"), listOf(dev.sumin.skeleton.account.events.AccountEventType.LOGIN_ID_SET, dev.sumin.skeleton.account.events.AccountEventType.LOGIN_ID_REMINDER_SENT).map { it.name })
    }

    @Test
    fun `an account has no login id until one is set`() {
        val now = java.time.Instant.EPOCH
        assertNull(Account("acc_1", null, false, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now).loginId)
    }
}
