package dev.sumin.skeleton.common.author

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthorDirectoryTest {
    @Test
    fun `the none directory knows nobody`() {
        assertTrue(AuthorDirectory.NONE.resolve(listOf("acc_1"), AuthorContext("board", "general")).isEmpty())
    }

    @Test
    fun `a context names the calling module and optionally its scope`() {
        assertEquals(AuthorContext("board", null), AuthorContext("board"))
        assertEquals("general", AuthorContext("board", "general").scope)
    }

    @Test
    fun `a card without a name is a valid answer`() {
        assertEquals(null, AuthorCard(null, null).name)
    }
}

class AuthorIdentitySpiTest {
    @Test
    fun `a card is not deleted by default and keeps copy and positional calls working`() {
        val card = AuthorCard("Nick", "0001")
        assertEquals(false, card.deleted)
        assertEquals(true, card.copy(deleted = true).deleted)
        assertEquals("Nick", card.copy(tag = null).name)
    }

    @Test
    fun `identities answer per account author and context`() {
        val identities = AuthorIdentities { accountId, authorId, context ->
            accountId == "acc_1" && authorId == "prf_1" && context == AuthorContext("board", "general")
        }
        assertTrue(identities.allows("acc_1", "prf_1", AuthorContext("board", "general")))
        assertEquals(false, identities.allows("acc_2", "prf_1", AuthorContext("board", "general")))
    }

    @Test
    fun `an adoption listener names itself lists pending accounts and adopts idempotently`() {
        val pending = mutableListOf("acc_1")
        val listener = object : AuthorAdoptionListener {
            override val name = "board"
            override fun pendingAccounts(limit: Int) = pending.take(limit)
            override fun adopt(accountId: String, authorId: String): Int = if (pending.remove(accountId)) 1 else 0
        }
        assertEquals(listOf("acc_1"), listener.pendingAccounts(10))
        assertEquals(1, listener.adopt("acc_1", "prf_1"))
        assertEquals(0, listener.adopt("acc_1", "prf_1"))
        assertTrue(listener.pendingAccounts(10).isEmpty())
    }
}
