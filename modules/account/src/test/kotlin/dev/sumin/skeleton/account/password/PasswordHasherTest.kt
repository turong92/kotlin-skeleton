package dev.sumin.skeleton.account.password

import dev.sumin.skeleton.account.AccountProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class PasswordHasherTest {
    private val encoder = PasswordEncoders.delegating(AccountProperties.Password(bcryptStrength = 4))
    private val hasher = PasswordHasher(encoder)

    @Test
    fun `new hashes carry the algorithm id so the algorithm can change later`() {
        val h = hasher.hash("tangerine-42-moon")
        assertTrue(h.startsWith("{bcrypt}"), h)
        assertTrue(hasher.matches("tangerine-42-moon", h))
        assertFalse(hasher.matches("tangerine-42-moon!", h))
    }

    @Test
    fun `hashes of the same password differ (salted)`() {
        assertTrue(hasher.hash("tangerine-42-moon") != hasher.hash("tangerine-42-moon"))
    }

    @Test
    fun `an unprefixed bcrypt hash from the old auth module still verifies and is flagged for upgrade`() {
        val legacy = BCryptPasswordEncoder(4).encode("old-password-1")!!
        assertTrue(hasher.matches("old-password-1", legacy))
        assertTrue(hasher.needsUpgrade(legacy))
        assertFalse(hasher.needsUpgrade(hasher.hash("old-password-1")))
    }

    @Test
    fun `a hash from a weaker work factor is flagged for upgrade`() {
        val weak = PasswordHasher(PasswordEncoders.delegating(AccountProperties.Password(bcryptStrength = 4))).hash("x-password-1")
        assertTrue(PasswordHasher(PasswordEncoders.delegating(AccountProperties.Password(bcryptStrength = 6))).needsUpgrade(weak))
    }

    @Test
    fun `garbage and empty hashes never match and never throw`() {
        assertFalse(hasher.matches("x", ""))
        assertFalse(hasher.matches("x", "not-a-hash"))
        assertFalse(hasher.matches("x", "{unknown}abc"))
        assertFalse(hasher.matches("x".repeat(500), hasher.hash("short-password-1")))
    }

    @Test
    fun `argon2 is opt-in and says what is missing when BouncyCastle is not on the classpath`() {
        val ex = assertFailsWith<IllegalStateException> { PasswordEncoders.delegating(AccountProperties.Password(encoder = "argon2")) }
        assertTrue("bcprov" in ex.message!!, ex.message)
        assertEquals("bcrypt", AccountProperties.Password().encoder)
    }
}
