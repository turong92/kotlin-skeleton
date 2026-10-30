package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.search.SearchDocument
import dev.sumin.skeleton.common.search.SearchSource
import dev.sumin.skeleton.common.search.SearchSourcePage

// 상품 → 검색 색인. `catalog` 는 `search` 를 모른다 - platform 의 포트(SearchIndexer · SearchSource)만 안다.

internal const val PRODUCT_SEARCH_TYPE = "product"

/** 제목 = [defaultLocale] 의 이름(없으면 첫 언어), 본문 = 모든 언어의 이름 · 설명, 범위 = 종류. 주인이 없다 - 판매 중인 상품은 로그인한 모두에게 보인다 */
internal fun CatalogProduct.toSearchDocument(defaultLocale: String) = SearchDocument(
    type = PRODUCT_SEARCH_TYPE,
    id = code,
    title = textIn(defaultLocale).name,
    body = texts.flatMap { listOfNotNull(it.name, it.description) }.joinToString("\n"),
    scope = kind.name,
    updatedAt = updatedAt,
)

/** 판매 중인 상품만 내놓는다(색인에 있는 문서 = 보여도 되는 문서). 재색인 · 검색을 나중에 붙인 앱의 채우기가 쓴다 */
class CatalogSearchSource(private val store: CatalogStore, private val defaultLocale: String) : SearchSource {
    override val type: String = PRODUCT_SEARCH_TYPE

    override fun page(after: String?, limit: Int): SearchSourcePage {
        val rest = store.listProducts().filter { it.selling && (after == null || it.code > after) }.sortedBy { it.code }
        val slice = rest.take(limit)
        return SearchSourcePage(slice.map { it.toSearchDocument(defaultLocale) }, if (rest.size > limit) slice.last().code else null)
    }
}
