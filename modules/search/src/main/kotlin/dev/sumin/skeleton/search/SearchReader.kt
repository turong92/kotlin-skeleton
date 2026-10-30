package dev.sumin.skeleton.search

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode

/** 검색 질의 - 질의어를 쪼개고, 결과 창 상한을 지키고, 엔진이 준 건에 발췌 · 강조를 붙인다 (엔진과 무관한 규칙은 여기 한 곳) */
class SearchReader(private val engine: SearchEngine) {
    fun search(query: String, types: Set<String>, scope: String?, sort: SearchSort, page: Int, size: Int, callerId: String?): SearchResultPage {
        if (page < 0 || size !in 1..MAX_SIZE) throw ApplicationException("page must be 0 or more and size 1..$MAX_SIZE", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        val phrases = SearchText.queryPhrases(query)
        if (phrases.isEmpty()) throw SearchException(SearchErrorCode.QUERY_TOO_SHORT, "Search query needs at least one word of two or more characters")
        if ((page.toLong() + 1) * size > MAX_WINDOW) throw SearchException(SearchErrorCode.PAGE_TOO_DEEP, "Only the first $MAX_WINDOW results can be paged")

        val hits = engine.search(SearchQuery(phrases, types, scope, callerId, sort, page * size, size))
        val results = hits.hits.map { hit ->
            val snippet = SearchText.snippet(hit.body, phrases)
            SearchResult(hit.type, hit.id, hit.title, snippet.text, SearchText.highlights(hit.title, phrases), snippet.highlights, hit.updatedAt)
        }
        return SearchResultPage(results, hits.total, page, size)
    }

    companion object {
        const val MAX_SIZE = 50
        const val MAX_WINDOW = 1_000
    }
}
