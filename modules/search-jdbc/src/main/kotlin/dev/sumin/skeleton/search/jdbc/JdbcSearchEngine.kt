package dev.sumin.skeleton.search.jdbc

import dev.sumin.skeleton.common.search.SearchDocument
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.search.SearchEngine
import dev.sumin.skeleton.search.SearchHit
import dev.sumin.skeleton.search.SearchHits
import dev.sumin.skeleton.search.SearchQuery
import dev.sumin.skeleton.search.SearchReader
import dev.sumin.skeleton.search.SearchText
import java.time.Instant
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 검색 엔진을 같은 DB 의 `search_documents` 로 구현한다(PostgreSQL: 2글자 조각 tsvector + GIN, MySQL: ngram FULLTEXT).
 * 쓰기는 **호출자 트랜잭션에 합류**한다(같은 DataSource 연결) - 커밋과 함께 보이고 롤백되면 없다. 트랜잭션 밖이면 문장 하나가 곧 한 트랜잭션이다.
 * MySQL InnoDB FULLTEXT 는 커밋 뒤에야 보인다 - 같은 트랜잭션 안의 질의에는 방금 쓴 문서가 안 잡힐 수 있다.
 */
class JdbcSearchEngine(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
) : SearchEngine {
    private val sql = SearchSql.of(dialect.vendor)
    private val tx = TransactionTemplate(transactionManager)

    override fun upsert(document: SearchDocument) = write(document)

    /** [upsert] 와 같은 시각 가드(원본이 더 새 쓰기를 옛 쪽 스냅숏으로 덮지 않는다) + 가드에 막혀도 `indexed_at` 은 갱신해 재색인 끝 정리가 살아 있는 문서를 지우지 않게 한다 */
    override fun reindex(document: SearchDocument) {
        write(document)
        jdbc.update("update search_documents set indexed_at = ${sql.dbNow} where doc_type = :t and doc_id = :i", mapOf("t" to document.type, "i" to document.id))
    }

    private fun write(document: SearchDocument) {
        // 정규화하면 길이가 늘 수 있다(호환 문자) - 열 길이에 맞춰 다시 자른다
        val title = SearchText.clip(SearchText.normalize(document.title), SearchDocument.MAX_TITLE)
        val body = SearchText.clip(SearchText.normalize(document.body), SearchText.MAX_BODY)
        val params = mutableMapOf<String, Any?>(
            "doc_type" to document.type,
            "doc_id" to document.id,
            "owner_id" to document.ownerId,
            "scope" to document.scope,
            "title" to title,
            "body" to body,
            "source_updated_at" to dialect.instantParam(document.updatedAt),
        )
        params += sql.extraWriteParams(title, body)
        jdbc.update(sql.upsert, params)
    }

    /**
     * 같은 종류의 재색인 쪽(`touch` 가 `search_rebuilds` 줄을 잠근 채 원본을 읽고 쓴다)과 겹치지 않게 그 줄을 **공유 잠금**으로 잡은 뒤 지운다 -
     * 재색인이 쓰는 동안은 기다렸다가 그 뒤에 지우고, 지우는 쪽 트랜잭션이 끝나기 전에는 재색인이 쪽을 읽지 못한다.
     * 안 그러면 원본을 읽은 뒤 지워진 문서를 재색인이 되살린다(판매 중지 상품이 검색에 남는다). 줄은 재색인 시작이 만든다 - 지우기는 줄을 **만들지 않고** 공유 잠금으로 읽기만 한다
     * (만들면 MySQL 에서 종류가 다른 지우기끼리 배타 잠금으로 드물게 교착한다). 한 번도 재색인한 적 없는 종류는 잠글 줄이 없지만 그때는 겹칠 재색인이 없다. 호출자 트랜잭션에 합류한다.
     */
    override fun delete(type: String, id: String) {
        tx.executeWithoutResult {
            jdbc.queryForList("select 1 from search_rebuilds where doc_type = :t for share", mapOf("t" to type))
            jdbc.update("delete from search_documents where doc_type = :t and doc_id = :i", mapOf("t" to type, "i" to id))
        }
    }

    override fun search(query: SearchQuery): SearchHits {
        val params = mutableMapOf<String, Any?>()
        val match = sql.match(query, params)
        val filters = buildString {
            append(" where ").append(match.where)
            if (query.callerId == null) append(" and d.owner_id is null")
            else { append(" and (d.owner_id is null or d.owner_id = :caller)"); params["caller"] = query.callerId }
            if (query.types.isNotEmpty()) { append(" and d.doc_type in (:types)"); params["types"] = query.types }
            if (query.scope != null) { append(" and d.scope = :scope"); params["scope"] = query.scope }
        }
        val hits = jdbc.query(
            "select d.doc_type, d.doc_id, d.title, d.body, d.source_updated_at from search_documents d$filters " +
                "order by ${match.rank}d.source_updated_at desc, d.id desc limit :limit offset :offset",
            params + mapOf("limit" to query.limit, "offset" to query.offset),
        ) { rs, _ ->
            SearchHit(rs.getString("doc_type"), rs.getString("doc_id"), rs.getString("title"), rs.getString("body"), dialect.readInstant(rs, "source_updated_at")!!)
        }
        // 결과 창 상한까지만 센다 - 큰 색인에서 모두 세지 않는다
        val total = jdbc.queryForObject(
            "select count(*) from (select 1 from search_documents d$filters limit ${SearchReader.MAX_WINDOW}) c", params, Long::class.java,
        ) ?: 0L
        return SearchHits(hits, total)
    }

    override fun eraseOwner(ownerId: String): Int = jdbc.update("delete from search_documents where owner_id = :o", mapOf("o" to ownerId))

    override fun deleteIndexedBefore(type: String, before: Instant): Int =
        jdbc.update("delete from search_documents where doc_type = :t and indexed_at < :b", mapOf("t" to type, "b" to dialect.instantParam(before)))

    override fun countByType(): Map<String, Long> =
        jdbc.query("select doc_type, count(*) as n from search_documents group by doc_type", emptyMap<String, Any>()) { rs, _ -> rs.getString("doc_type") to rs.getLong("n") }.toMap()
}
