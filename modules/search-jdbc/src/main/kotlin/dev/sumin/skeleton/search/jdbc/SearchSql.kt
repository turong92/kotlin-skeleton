package dev.sumin.skeleton.search.jdbc

import dev.sumin.skeleton.search.SearchQuery
import dev.sumin.skeleton.search.SearchSort
import dev.sumin.skeleton.search.SearchText

/** 방언마다 다른 SQL 두 조각 - 쓰기(조각 계산 포함)와 질의의 일치 · 순위 식. 나머지는 [JdbcSearchEngine] 이 같다 */
internal sealed interface SearchSql {
    /** 이름 있는 파라미터: doc_type doc_id owner_id scope title body source_updated_at indexed_at force (+ 방언별 [extraWriteParams]) */
    val upsert: String

    fun extraWriteParams(title: String, body: String): Map<String, Any?>

    /** 일치 조건(WHERE 조각)과 순위 식(ORDER BY 조각)을 만들고 [params] 에 파라미터를 더한다 */
    fun match(query: SearchQuery, params: MutableMap<String, Any?>): Match

    data class Match(val where: String, val rank: String)

    companion object {
        fun of(vendor: String): SearchSql = when (vendor) {
            "postgresql" -> PostgresSearchSql
            "mysql" -> MySqlSearchSql
            else -> error("search-jdbc supports postgresql and mysql, not $vendor")
        }
    }
}

internal object PostgresSearchSql : SearchSql {
    override val upsert = """
        insert into search_documents (doc_type, doc_id, owner_id, scope, title, body, terms, source_updated_at, indexed_at)
        values (:doc_type, :doc_id, :owner_id, :scope, :title, :body,
                setweight(to_tsvector('simple', :title_grams), 'A') || setweight(to_tsvector('simple', :body_grams), 'B'),
                :source_updated_at, :indexed_at)
        on conflict (doc_type, doc_id) do update set
            owner_id = excluded.owner_id, scope = excluded.scope, title = excluded.title, body = excluded.body, terms = excluded.terms,
            source_updated_at = excluded.source_updated_at, indexed_at = excluded.indexed_at
        where :force or search_documents.source_updated_at <= excluded.source_updated_at
    """.trimIndent()

    override fun extraWriteParams(title: String, body: String): Map<String, Any?> =
        mapOf("title_grams" to SearchText.grams(title), "body_grams" to SearchText.grams(body))

    override fun match(query: SearchQuery, params: MutableMap<String, Any?>): SearchSql.Match {
        val tsquery = query.phrases.withIndex().joinToString(" && ") { (i, phrase) ->
            params["p$i"] = SearchText.phraseGrams(phrase)
            "phraseto_tsquery('simple', :p$i)"
        }
        val rank = if (query.sort == SearchSort.RELEVANCE) "ts_rank_cd(d.terms, ($tsquery)) desc, " else ""
        return SearchSql.Match("d.terms @@ ($tsquery)", rank)
    }
}

internal object MySqlSearchSql : SearchSql {
    // ON DUPLICATE KEY UPDATE 의 대입은 왼쪽부터 평가되어 뒤 대입이 앞 대입의 결과를 본다 - 비교에 쓰는 source_updated_at 은 맨 끝에 대입한다
    private const val NEWER = "(:force or search_documents.source_updated_at <= incoming.source_updated_at)"

    override val upsert = """
        insert into search_documents (doc_type, doc_id, owner_id, scope, title, body, source_updated_at, indexed_at)
        values (:doc_type, :doc_id, :owner_id, :scope, :title, :body, :source_updated_at, :indexed_at) as incoming
        on duplicate key update
            owner_id = if($NEWER, incoming.owner_id, search_documents.owner_id),
            scope = if($NEWER, incoming.scope, search_documents.scope),
            title = if($NEWER, incoming.title, search_documents.title),
            body = if($NEWER, incoming.body, search_documents.body),
            indexed_at = if($NEWER, incoming.indexed_at, search_documents.indexed_at),
            source_updated_at = if($NEWER, incoming.source_updated_at, search_documents.source_updated_at)
    """.trimIndent()

    override fun extraWriteParams(title: String, body: String): Map<String, Any?> = emptyMap()

    override fun match(query: SearchQuery, params: MutableMap<String, Any?>): SearchSql.Match {
        // 덩어리는 글자 · 숫자뿐이라 따옴표를 따로 이스케이프할 것이 없다 (SearchText.runs)
        params["q"] = query.phrases.joinToString(" ") { "+\"$it\"" }
        val rank = if (query.sort == SearchSort.RELEVANCE)
            "(match(d.title) against (:q in boolean mode) * 2 + match(d.title, d.body) against (:q in boolean mode)) desc, " else ""
        // ngram 구문 일치는 낱말 경계를 넘어 맞는다(`ab bc` 가 질의 `abc` 에 맞음 - 실측). 3글자 이상 덩어리는 부분 문자열인지 한 번 더 확인한다
        // (FULLTEXT 가 좁힌 뒤라 비용이 작다. 덩어리는 글자 · 숫자뿐이라 LIKE 와일드카드가 섞이지 않는다)
        val confirm = query.phrases.withIndex().filter { (_, p) -> p.codePointCount(0, p.length) > 2 }.joinToString("") { (i, phrase) ->
            params["like$i"] = "%$phrase%"
            " and (d.title like :like$i or d.body like :like$i)"
        }
        return SearchSql.Match("match(d.title, d.body) against (:q in boolean mode)$confirm", rank)
    }
}
