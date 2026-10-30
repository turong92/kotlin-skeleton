package dev.sumin.skeleton.search

import dev.sumin.skeleton.common.search.SearchDocument
import dev.sumin.skeleton.common.search.SearchIndexer
import java.time.Instant

enum class SearchSort { RELEVANCE, RECENT }

/**
 * 엔진에 건네는 질의. [phrases] 는 [SearchText.queryPhrases] 가 쪼갠 덩어리(모두 맞아야, 부분 문자열 일치)다.
 * 보이는 문서 = 주인이 없거나 [callerId] 가 주인인 것 (비로그인은 [callerId] 가 null → 주인 없는 것만). [types] 가 비면 모든 종류
 */
data class SearchQuery(
    val phrases: List<String>,
    val types: Set<String>,
    val scope: String?,
    val callerId: String?,
    val sort: SearchSort,
    val offset: Int,
    val limit: Int,
)

/** 엔진이 돌려주는 한 건 - 발췌 · 강조는 [SearchReader] 가 [SearchText] 로 만든다 (엔진 무관) */
data class SearchHit(val type: String, val id: String, val title: String, val body: String, val updatedAt: Instant)

/** [total] 은 결과 창 상한([SearchReader.MAX_WINDOW])까지만 센 값이다 */
data class SearchHits(val hits: List<SearchHit>, val total: Long)

/**
 * 색인 쓰기(+[SearchIndexer])와 질의. **두 번째 구현이 계획에 있다**(바깥 엔진) - 같은 DB 구현은 `search-jdbc`.
 * 두 방언의 순위 값은 다르므로 점수는 밖으로 내지 않고 순서만 낸다.
 */
interface SearchEngine : SearchIndexer {
    /** 원본이 진실일 때의 덮어쓰기 - [upsert] 와 달리 옛 시각이어도 쓴다. 재색인이 쓴다 */
    fun reindex(document: SearchDocument)

    fun search(query: SearchQuery): SearchHits

    /** 이 계정이 주인인 문서를 모두 지운다 (탈퇴). 지운 개수 */
    fun eraseOwner(ownerId: String): Int

    /** [type] 에서 [before] 전에 색인된 문서를 지운다 (재색인 끝에 사라진 원본 정리). 지운 개수 */
    fun deleteIndexedBefore(type: String, before: Instant): Int

    fun countByType(): Map<String, Long>
}

data class SearchRebuildState(val running: Boolean, val lastRebuiltAt: Instant?)

/** 재색인 시작과 상태. 구현(`search-jdbc`)은 큐에 작업을 넣는다 */
interface SearchRebuilds {
    /** 시작하고 true. 같은 종류가 이미 돌고 있으면 아무것도 하지 않고 false */
    fun start(type: String): Boolean

    fun states(): Map<String, SearchRebuildState>
}

/** 응답 한 건 - [snippet] 은 본문 발췌(없으면 빈 문자열), 강조는 각 문자열의 `[시작, 끝)` UTF-16 위치 */
data class SearchResult(
    val type: String,
    val id: String,
    val title: String,
    val snippet: String,
    val titleHighlights: List<Pair<Int, Int>>,
    val snippetHighlights: List<Pair<Int, Int>>,
    val updatedAt: Instant,
)

data class SearchResultPage(val results: List<SearchResult>, val total: Long, val page: Int, val size: Int)

data class SearchTypeStatus(val type: String, val documents: Long, val rebuilding: Boolean, val lastRebuiltAt: Instant?)
