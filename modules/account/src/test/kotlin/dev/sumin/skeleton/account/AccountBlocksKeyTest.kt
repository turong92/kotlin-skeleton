package dev.sumin.skeleton.account

import java.time.Duration
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AccountBlocksKeyTest {
    private val time = MutableTime()

    @Test
    fun `the built-in local blocks use a random key per instance - no key is compiled into the module`() {
        assertNotEquals(AccountBlocks.local(time).emailHash("a@example.com"), AccountBlocks.local(time).emailHash("a@example.com"))
    }

    @Test
    fun `the same key hashes the same address the same way, whatever the case`() {
        val blocks = AccountBlocks.local(time)
        assertEquals(blocks.emailHash("a@example.com"), blocks.emailHash(" A@Example.com "))
    }

    @Test
    fun `a login id hash is the HMAC-SHA256 of the login-id prefix and the id under the blocks key`() {
        val key = "a-long-random-block-secret".toByteArray()
        val blocks = AccountBlocks(InMemoryAccountBlockStore(), key, Duration.ZERO, time)
        val expected = HexFormat.of().formatHex(Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(key, "HmacSHA256")) }.doFinal("login-id:ann.kim".toByteArray()))
        assertEquals(expected, blocks.loginIdHash("ann.kim"))
        assertEquals(64, blocks.loginIdHash("ann.kim").length)
    }

    @Test
    fun `a login id hash never collides with an email or sign-in method hash of the same text, and depends on the key`() {
        val a = AccountBlocks(InMemoryAccountBlockStore(), "key-a".toByteArray(), Duration.ZERO, time)
        val b = AccountBlocks(InMemoryAccountBlockStore(), "key-b".toByteArray(), Duration.ZERO, time)
        assertNotEquals(a.loginIdHash("ann"), a.identityHash("login-id", "ann"))
        assertNotEquals(a.loginIdHash("ann"), a.loginIdHash("bob"))
        assertNotEquals(a.loginIdHash("ann"), b.loginIdHash("ann"))
    }
}
