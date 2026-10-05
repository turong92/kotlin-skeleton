package dev.sumin.skeleton.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BoardContentRulesTest {
    private val rules = BoardContentRules(BoardProperties(titleMaxLength = 10, bodyMaxLength = 30, commentMaxLength = 20, maxAttachments = 2))

    private fun invalid(block: () -> Unit) {
        val e = assertFailsWith<BoardException> { block() }
        assertEquals(BoardErrorCode.CONTENT_INVALID, e.errorCode)
    }

    @Test
    fun `a title is trimmed, stripped of control characters and kept on one line`() {
        assertEquals("a b c", rules.title("  a\u0000 b\nc \u0007"))
    }

    @Test
    fun `a blank or too long title is CONTENT_INVALID`() {
        invalid { rules.title("   ") }
        invalid { rules.title(null) }
        invalid { rules.title("12345678901") }
        assertEquals("1234567890", rules.title("1234567890"))
    }

    @Test
    fun `length is counted in code points so one emoji is one character`() {
        assertEquals("😀".repeat(10), rules.title("😀".repeat(10)))
        invalid { rules.title("😀".repeat(11)) }
    }

    @Test
    fun `a body keeps its newlines and tabs, normalises CRLF, and does not interpret HTML`() {
        assertEquals("line1\nline2\t<b>x</b>", rules.postBody("line1\r\nline2\t<b>x</b>\u0000"))
    }

    @Test
    fun `bidi override characters are stripped from every text`() {
        assertEquals("abc", rules.commentBody("a‮b⁦c"))
    }

    @Test
    fun `body and comment limits come from the properties`() {
        invalid { rules.postBody("x".repeat(31)) }
        invalid { rules.commentBody("x".repeat(21)) }
        invalid { rules.commentBody("\n\t ") }
    }

    @Test
    fun `attachments are limited, de-duplicated and must not be blank`() {
        assertEquals(listOf("a/1", "b/2"), rules.attachments(listOf("a/1", " b/2 ", "a/1")))
        assertEquals(emptyList(), rules.attachments(null))
        invalid { rules.attachments(listOf("a", "b", "c")) }
        invalid { rules.attachments(listOf("a", " ")) }
        invalid { rules.attachments(listOf("k".repeat(1025))) }
    }

    @Test
    fun `a search term is trimmed, blank means none, and it is capped`() {
        assertEquals("hello", rules.searchTerm("  hello "))
        assertNull(rules.searchTerm("   "))
        assertNull(rules.searchTerm(null))
        assertEquals(100, rules.searchTerm("x".repeat(500))!!.length)
    }
}
