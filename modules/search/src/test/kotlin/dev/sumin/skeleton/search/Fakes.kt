package dev.sumin.skeleton.search

import dev.sumin.skeleton.common.search.SearchDocument
import dev.sumin.skeleton.common.search.SearchSource
import dev.sumin.skeleton.common.search.SearchSourcePage
import java.time.Instant

/** 질의를 기록하고 정해 둔 결과를 돌려주는 엔진 - 서비스 규칙(질의 쪼개기 · 창 상한 · 발췌)만 본다. 저장 동작은 search-jdbc 의 dbTest 가 본다 */
class RecordingSearchEngine(var hits: SearchHits = SearchHits(emptyList(), 0)) : SearchEngine {
    var lastQuery: SearchQuery? = null
    var documents: Map<String, Long> = emptyMap()
    var erasedOwner: String? = null

    override fun upsert(document: SearchDocument) = Unit
    override fun reindex(document: SearchDocument) = Unit
    override fun delete(type: String, id: String) = Unit
    override fun search(query: SearchQuery): SearchHits = hits.also { lastQuery = query }
    override fun eraseOwner(ownerId: String): Int = 1.also { erasedOwner = ownerId }
    override fun deleteIndexedBefore(type: String, before: Instant): Int = 0
    override fun countByType(): Map<String, Long> = documents
}

class FakeRebuilds(var states: Map<String, SearchRebuildState> = emptyMap(), var accept: Boolean = true) : SearchRebuilds {
    val started = mutableListOf<String>()
    override fun start(type: String): Boolean = accept.also { if (it) started += type }
    override fun states(): Map<String, SearchRebuildState> = states
}

class FixedSource(override val type: String) : SearchSource {
    override fun page(after: String?, limit: Int) = SearchSourcePage(emptyList(), null)
}
