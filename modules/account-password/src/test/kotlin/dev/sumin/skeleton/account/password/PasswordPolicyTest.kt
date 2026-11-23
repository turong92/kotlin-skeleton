package dev.sumin.skeleton.account.password

import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.PasswordViolation
import dev.sumin.skeleton.account.PasswordViolation.BREACHED
import dev.sumin.skeleton.account.PasswordViolation.CONTAINS_EMAIL
import dev.sumin.skeleton.account.PasswordViolation.NEEDS_DIGIT
import dev.sumin.skeleton.account.PasswordViolation.NEEDS_LETTER
import dev.sumin.skeleton.account.PasswordViolation.NEEDS_SYMBOL
import dev.sumin.skeleton.account.PasswordViolation.TOO_COMMON
import dev.sumin.skeleton.account.PasswordViolation.TOO_LONG
import dev.sumin.skeleton.account.PasswordViolation.TOO_SHORT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordPolicyTest {
    private fun policy(props: AccountProperties.Password = AccountProperties.Password(), breached: BreachedPasswordCheck? = null) = DefaultPasswordPolicy(props, breached)

    private fun PasswordPolicy.violations(password: String, email: String? = "ann@example.com") = check(password, email)

    @Test
    fun `a decent password passes`() = assertEquals(emptyList(), policy().violations("tangerine-42-moon"))

    @Test
    fun `too short and too long are reported, length counts utf-8 bytes against the bcrypt ceiling`() {
        assertEquals(listOf(TOO_SHORT), policy().violations("a1b2c3"))
        assertEquals(listOf(TOO_LONG), policy().violations("a1".repeat(40)))
        // 25 Hangul syllables = 75 bytes > 72 even though only 25 characters
        assertTrue(TOO_LONG in policy().violations("가".repeat(24) + "1a"))
    }

    @Test
    fun `letter digit and symbol requirements follow the configuration`() {
        assertEquals(listOf(NEEDS_LETTER), policy().violations("5820194736201"))
        assertEquals(listOf(NEEDS_DIGIT), policy().violations("tangerine-moon"))
        assertEquals(listOf(NEEDS_SYMBOL), policy(AccountProperties.Password(requireSymbol = true)).violations("tangerine42moon"))
        assertEquals(emptyList(), policy(AccountProperties.Password(requireDigit = false, requireLetter = false)).violations("tangerine-moon"))
    }

    @Test
    fun `the password must not contain the email's local part, case-insensitively`() {
        assertEquals(listOf(CONTAINS_EMAIL), policy().violations("Xx-ANN-sunrise-1", "ann@example.com").filter { it == CONTAINS_EMAIL })
        assertEquals(emptyList(), policy(AccountProperties.Password(forbidEmailLocalPart = false)).violations("Xx-ANN-sunrise-1", "ann@example.com"))
    }

    @Test
    fun `very short local parts are not matched (every password would contain 'a')`() {
        assertEquals(emptyList(), policy().violations("tangerine-42-moon", "a@example.com"))
    }

    @Test
    fun `an offline list of common passwords is rejected`() {
        assertTrue(TOO_COMMON in policy().violations("password123"))
        assertTrue(TOO_COMMON in policy().violations("Qwerty12345"))
        assertEquals(emptyList(), policy(AccountProperties.Password(denyCommon = false)).violations("password123"))
    }

    @Test
    fun `the breached-password hook runs only for passwords that passed the cheap rules and is optional`() {
        val seen = mutableListOf<String>()
        val hook = BreachedPasswordCheck { seen += it; it == "tangerine-42-moon" }
        assertEquals(listOf(BREACHED), policy(breached = hook).violations("tangerine-42-moon"))
        assertEquals(emptyList(), policy(breached = hook).violations("another-fine-pass-7"))
        policy(breached = hook).violations("short")
        assertEquals(listOf("tangerine-42-moon", "another-fine-pass-7"), seen, "the hook must not see passwords that already failed the local rules")
    }

    @Test
    fun `a failing breach hook never blocks sign-up (fails open) but is not silent`() {
        val hook = BreachedPasswordCheck { error("network down") }
        assertEquals(emptyList(), policy(breached = hook).violations("tangerine-42-moon"))
    }

    @Test
    fun `describe exposes what the UI needs without the deny list`() {
        val d = policy().describe()
        assertEquals(10, d.minLength)
        assertEquals(72, d.maxBytes)
        assertTrue(d.requireLetter && d.requireDigit && !d.requireSymbol && d.forbidEmailLocalPart)
    }

    @Test
    fun `bcrypt with a byte limit above 72 fails at startup instead of a 500 on sign-up`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { dev.sumin.skeleton.account.AccountProperties.Password(maxBytes = 100) }
        dev.sumin.skeleton.account.AccountProperties.Password(maxBytes = 100, encoder = "argon2")
    }
}
