package dev.sumin.skeleton.account

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
}
