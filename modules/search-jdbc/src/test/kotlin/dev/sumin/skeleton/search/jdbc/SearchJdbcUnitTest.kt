package dev.sumin.skeleton.search.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SearchJdbcUnitTest {
    @Test
    fun `ngram size warning only when the size is known and not two`() {
        assertNull(ngramWarning(2))
        assertNull(ngramWarning(null))
        assertNotNull(ngramWarning(3))
    }

    @Test
    fun `rebuild payload round-trips any cursor text`() {
        val cursor = "id \"quoted\" \\ 한글 😀"
        val parsed = JdbcSearchRebuilds.parse(JdbcSearchRebuilds.payload("note", "123e4567-e89b-12d3-a456-426614174000", cursor))
        assertEquals(JdbcSearchRebuilds.RebuildPayload("note", "123e4567-e89b-12d3-a456-426614174000", cursor), parsed)
        assertNull(JdbcSearchRebuilds.parse(JdbcSearchRebuilds.payload("note", "123e4567-e89b-12d3-a456-426614174000", null))?.after)
        assertNull(JdbcSearchRebuilds.parse("{}"))
    }
}
