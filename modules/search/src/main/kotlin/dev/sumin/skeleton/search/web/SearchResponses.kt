package dev.sumin.skeleton.search.web

import dev.sumin.skeleton.search.SearchResult
import dev.sumin.skeleton.search.SearchTypeStatus
import java.time.Instant

/** 검색 결과 한 건. 강조는 `titleHighlights` · `snippetHighlights` 의 `[시작, 끝)` UTF-16 위치 쌍 목록 - 점수는 싣지 않는다(두 방언의 값이 다르다) */
data class SearchResultResponse(
    val type: String,
    val id: String,
    val title: String,
    val snippet: String,
    val titleHighlights: List<List<Int>>,
    val snippetHighlights: List<List<Int>>,
    val updatedAt: Instant,
)

fun SearchResult.toResponse() = SearchResultResponse(
    type, id, title, snippet,
    titleHighlights.map { listOf(it.first, it.second) },
    snippetHighlights.map { listOf(it.first, it.second) },
    updatedAt,
)

data class SearchTypeResponse(val type: String, val documents: Long, val rebuilding: Boolean, val lastRebuiltAt: Instant?)

fun SearchTypeStatus.toResponse() = SearchTypeResponse(type, documents, rebuilding, lastRebuiltAt)

data class RebuildRequest(val type: String? = null)

data class RebuildAccepted(val type: String)
