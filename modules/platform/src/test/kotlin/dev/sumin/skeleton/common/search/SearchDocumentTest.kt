package dev.sumin.skeleton.common.search

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SearchDocumentTest {
    private val now = Instant.parse("2026-10-30T00:00:00Z")

    private fun doc(
        type: String = "note",
        id: String = "n1",
        title: String = "제목",
        body: String = "",
        ownerId: String? = null,
        scope: String? = null,
    ) = SearchDocument(type, id, title, body, ownerId, scope, now)

    @Test
    fun `a plain document is accepted`() {
        assertEquals("note", doc().type)
        doc(type = "a.b_c-1", id = "x".repeat(64), title = "t".repeat(300), ownerId = "o".repeat(64), scope = "s".repeat(64))
    }

    @Test
    fun `type must be 1 to 32 chars of lowercase letters digits dot underscore hyphen`() {
        for (bad in listOf("", "Note", "no te", "x".repeat(33), "노트", "a/b", "a:b"))
            assertFailsWith<IllegalArgumentException>("type=$bad") { doc(type = bad) }
    }

    @Test
    fun `id title scope owner have length limits`() {
        assertFailsWith<IllegalArgumentException> { doc(id = "") }
        assertFailsWith<IllegalArgumentException> { doc(id = "x".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { doc(title = "t".repeat(301)) }
        assertFailsWith<IllegalArgumentException> { doc(scope = "s".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { doc(ownerId = "o".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { doc(ownerId = "") }
        assertFailsWith<IllegalArgumentException> { doc(scope = "") }
    }
}
