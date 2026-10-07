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
