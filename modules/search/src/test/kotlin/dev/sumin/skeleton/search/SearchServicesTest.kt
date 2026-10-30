package dev.sumin.skeleton.search

import dev.sumin.skeleton.common.ApplicationException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SearchServicesTest {
    private val at = Instant.parse("2026-10-30T00:00:00Z")
    private val engine = RecordingSearchEngine()
    private val reader = SearchReader(engine)

    private fun searchError(block: () -> Unit): SearchErrorCode =
        assertFailsWith<SearchException>(block = block).searchError

    // ---- 질의 ----

    @Test
    fun `a query with nothing searchable is QUERY_TOO_SHORT`() {
        assertEquals(SearchErrorCode.QUERY_TOO_SHORT, searchError { reader.search("a * \"", emptySet(), null, SearchSort.RELEVANCE, 0, 20, "acc") })
        assertNull(engine.lastQuery)
    }

    @Test
    fun `the engine gets the phrases caller types scope sort and window`() {
        reader.search("가방끈 'x' 길이", setOf("note", "product"), "bag", SearchSort.RECENT, 2, 10, "acc_1")
        val query = engine.lastQuery!!
        assertEquals(listOf("가방끈", "길이"), query.phrases)
        assertEquals(setOf("note", "product"), query.types)
        assertEquals("bag", query.scope)
        assertEquals("acc_1", query.callerId)
        assertEquals(SearchSort.RECENT, query.sort)
        assertEquals(20, query.offset)
        assertEquals(10, query.limit)
    }

    @Test
    fun `the result window ends at the 1000th result`() {
        reader.search("가방끈", emptySet(), null, SearchSort.RELEVANCE, 19, 50, null)   // 950..999 까지는 된다
        assertEquals(950, engine.lastQuery!!.offset)
        assertEquals(SearchErrorCode.PAGE_TOO_DEEP, searchError { reader.search("가방끈", emptySet(), null, SearchSort.RELEVANCE, 20, 50, null) })
        assertEquals(SearchErrorCode.PAGE_TOO_DEEP, searchError { reader.search("가방끈", emptySet(), null, SearchSort.RELEVANCE, 100, 10, null) })
    }

    @Test
    fun `size is 1 to 50 and page is not negative`() {
        assertFailsWith<ApplicationException> { reader.search("가방끈", emptySet(), null, SearchSort.RELEVANCE, 0, 51, null) }
        assertFailsWith<ApplicationException> { reader.search("가방끈", emptySet(), null, SearchSort.RELEVANCE, 0, 0, null) }
        assertFailsWith<ApplicationException> { reader.search("가방끈", emptySet(), null, SearchSort.RELEVANCE, -1, 20, null) }
    }

    @Test
    fun `results carry the title ranges and a body snippet with ranges`() {
        engine.hits = SearchHits(listOf(SearchHit("note", "n1", "가방끈 이야기", "오늘은 가방끈이 짧다", at)), total = 1)
        val page = reader.search("방끈", emptySet(), null, SearchSort.RELEVANCE, 0, 20, "acc")
        val result = page.results.single()
        assertEquals("note", result.type)
        assertEquals("n1", result.id)
        assertEquals("가방끈 이야기", result.title)
        assertEquals(listOf(1 to 3), result.titleHighlights)
        assertEquals("오늘은 가방끈이 짧다", result.snippet)
        assertEquals(listOf(5 to 7), result.snippetHighlights)
        assertEquals(at, result.updatedAt)
        assertEquals(1, page.total)
    }

    // ---- 운영 ----

    @Test
    fun `types lists source types and indexed types with counts and rebuild state`() {
        engine.documents = mapOf("note" to 3, "old" to 1)
        val rebuilds = FakeRebuilds(mapOf("note" to SearchRebuildState(running = true, lastRebuiltAt = at)))
        val admin = SearchAdmin(engine, rebuilds) { listOf(FixedSource("note"), FixedSource("product")) }
        assertEquals(
            listOf(
                SearchTypeStatus("note", 3, true, at),
                SearchTypeStatus("old", 1, false, null),
                SearchTypeStatus("product", 0, false, null),
            ),
            admin.types(),
        )
    }

    @Test
    fun `rebuild of a type without a source is NOT_FOUND`() {
        val admin = SearchAdmin(engine, FakeRebuilds()) { listOf(FixedSource("note")) }
        assertEquals(SearchErrorCode.SOURCE_NOT_FOUND, searchError { admin.rebuild("nope") })
    }

    @Test
    fun `rebuild of a running type is a conflict and a free one starts`() {
        val rebuilds = FakeRebuilds(accept = false)
        val admin = SearchAdmin(engine, rebuilds) { listOf(FixedSource("note")) }
        assertEquals(SearchErrorCode.REBUILD_RUNNING, searchError { admin.rebuild("note") })
        rebuilds.accept = true
        admin.rebuild("note")
        assertEquals(listOf("note"), rebuilds.started)
    }
}
