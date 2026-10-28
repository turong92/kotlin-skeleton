package dev.sumin.skeleton.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NotificationEventTest {
    private fun event(link: String? = null, collapseKey: String? = null) =
        NotificationEvent(topic = "t", type = "x", link = link, collapseKey = collapseKey)

    @Test
    fun `link and collapse key default to null so existing publishers are unchanged`() {
        val e = NotificationEvent(topic = "t", type = "x")
        assertNull(e.link)
        assertNull(e.collapseKey)
    }

    @Test
    fun `link accepts an in-app absolute path`() {
        assertEquals("/notes/1?tab=a#b", event(link = "/notes/1?tab=a#b").link)
        assertEquals("/", event(link = "/").link)
        assertEquals(1000, event(link = "/" + "a".repeat(999)).link!!.length)
    }

    @Test
    fun `link must stay inside the app - no scheme, no protocol-relative, no relative path`() {
        listOf("//evil.example/x", "http://evil.example", "https://evil.example", "javascript:alert(1)", "notes/1", "", " /notes", "\\\\evil.example", "/\\evil.example")
            .forEach { bad -> assertFailsWith<IllegalArgumentException>(bad) { event(link = bad) } }
    }

    @Test
    fun `link longer than 1000 characters is rejected`() {
        assertFailsWith<IllegalArgumentException> { event(link = "/" + "a".repeat(1000)) }
    }

    @Test
    fun `link must not carry control characters`() {
        assertFailsWith<IllegalArgumentException> { event(link = "/a\nb") }
    }

    @Test
    fun `collapse key must be non blank and at most 255 characters`() {
        assertEquals("k", event(collapseKey = "k").collapseKey)
        assertEquals(255, event(collapseKey = "k".repeat(255)).collapseKey!!.length)
        assertFailsWith<IllegalArgumentException> { event(collapseKey = " ") }
        assertFailsWith<IllegalArgumentException> { event(collapseKey = "") }
        assertFailsWith<IllegalArgumentException> { event(collapseKey = "k".repeat(256)) }
    }
}
