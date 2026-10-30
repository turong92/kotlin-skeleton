package dev.sumin.skeleton.common.search

import java.time.Instant

// 검색 색인에 문서를 밀어 넣는 계약 — 타입만 있고 빈은 없다 (`SearchIndexer` 구현은 `search-jdbc` 가 내놓는다).
// `catalog` · 앱이 `search` 를 컴파일에 끌어오지 않고 밀어 넣으려고 platform 에 둔다 (`RewardIssuer` · `PermissionDeclaration` 과 같은 이유).
// 부르는 쪽은 `ObjectProvider<SearchIndexer>.ifAvailable` 로 받는다 - 검색을 안 붙인 앱에는 아무 일도 없다.

/**
 * 검색 색인의 문서 한 건. `(type, id)` 가 한 문서다.
 *
 * **색인에 있는 문서 = 보여도 되는 문서**다. [ownerId] 가 없으면 로그인한 모두(앱이 켜면 비로그인도)에게 제목 · 발췌가 보이고, 있으면 그 계정에게만 보인다.
 * 보이면 안 되는 상태(판매 중지 · 숨김 · 지움)가 되면 밀어 넣는 쪽이 [SearchIndexer.delete] 한다.
 *
 * @property title 검색 결과에 그대로 보이는 제목 (300자까지)
 * @property body 검색되는 본문 — 앞 16,000자만 색인한다 (모듈이 자른다)
 * @property scope 결과를 한 범위로 좁히는 열린 값 (예: 상품 종류) - 모듈은 뜻을 모른다
 * @property updatedAt 원본이 마지막으로 바뀐 시각 - 더 옛것을 밀어 넣으면 무시한다
 */
data class SearchDocument(
    val type: String,
    val id: String,
    val title: String,
    val body: String = "",
    val ownerId: String? = null,
    val scope: String? = null,
    val updatedAt: Instant,
) {
    init {
        require(TYPE.matches(type)) { "search document type must match ${TYPE.pattern}: $type" }
        require(id.length in 1..MAX_ID) { "search document id must be 1..$MAX_ID chars" }
        require(title.length <= MAX_TITLE) { "search document title must be at most $MAX_TITLE chars" }
        require(ownerId == null || ownerId.length in 1..MAX_OWNER) { "search document ownerId must be 1..$MAX_OWNER chars" }
        require(scope == null || scope.length in 1..MAX_SCOPE) { "search document scope must be 1..$MAX_SCOPE chars" }
    }

    companion object {
        val TYPE = Regex("[a-z0-9._-]{1,32}")
        const val MAX_ID = 64
        const val MAX_TITLE = 300
        const val MAX_OWNER = 64
        const val MAX_SCOPE = 64
    }
}

/**
 * 색인에 쓰는 쪽. **호출자 트랜잭션 안에서 부른다** - 같은 DB 구현은 그 트랜잭션에 함께 쓰므로 커밋과 함께 보이고 롤백되면 없다.
 * 바깥 엔진 구현은 같은 호출을 아웃박스 줄로 받아 일꾼이 보낸다 - 부르는 쪽 코드는 그대로다.
 */
interface SearchIndexer {
    /** 없으면 넣고 있으면 덮는다. 색인에 있는 것보다 [SearchDocument.updatedAt] 이 옛것이면 무시한다 */
    fun upsert(document: SearchDocument)

    /** 없으면 아무 일도 없다 */
    fun delete(type: String, id: String)
}

/**
 * 한 종류의 문서를 원본에서 다시 읽는 쪽 - 재색인 · 검색을 나중에 붙인 앱의 채우기 · 바깥 엔진으로 옮길 때 처음 채우기가 쓴다.
 * 빈으로 등록하면 `search` 가 [type] 으로 찾는다.
 */
interface SearchSource {
    /** 이 소스가 내놓는 문서 종류 ([SearchDocument.type]) */
    val type: String

    /** [after] 다음부터 [limit] 개까지, 안정된 순서(id 순 등)로. 더 없으면 [SearchSourcePage.next] 가 null. 색인에 있어야 하는(보여도 되는) 문서만 내놓는다 */
    fun page(after: String?, limit: Int): SearchSourcePage
}

data class SearchSourcePage(val documents: List<SearchDocument>, val next: String?)
