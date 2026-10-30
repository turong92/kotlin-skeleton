package dev.sumin.skeleton.search.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.search.SearchRebuildState
import dev.sumin.skeleton.search.SearchRebuilds
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

const val SEARCH_REBUILD_JOB_TYPE = "search-rebuild"

/**
 * 종류별 재색인의 진행 표시(`search_rebuilds`)와 시작. 줄 하나가 「지금 도는 한 번」의 열쇠다 - 시작은 줄을 잠그고 `running` 이 아니거나 멈춘(진행 표시가
 * [staleAfter] 동안 없는) 때만 새 `run_id` 로 넘겨받는다. 같은 줄에 대한 동시 시작은 한쪽만 이긴다. 큐 작업은 시작과 같은 트랜잭션에 넣는다.
 */
class JdbcSearchRebuilds(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
    private val time: TimeProvider,
    private val queue: JobQueue,
    private val staleAfter: Duration,
) : SearchRebuilds {
    private val tx = TransactionTemplate(transactionManager)
    private val dbNow = SearchSql.of(dialect.vendor).dbNow

    override fun start(type: String): Boolean = tx.execute {
        val now = time.now()
        val run = UUID.randomUUID().toString()
        jdbc.update(
            dialect.insertIgnore("search_rebuilds", listOf("doc_type", "run_id", "running", "started_at", "updated_at"), listOf("doc_type")),
            mapOf("doc_type" to type, "run_id" to run, "running" to true, "started_at" to dialect.instantParam(now), "updated_at" to dialect.instantParam(now)),
        )
        val row = jdbc.query("select run_id, running, updated_at from search_rebuilds where doc_type = :t for update", mapOf("t" to type)) { rs, _ ->
            Triple(rs.getString("run_id"), rs.getBoolean("running"), dialect.readInstant(rs, "updated_at")!!)
        }.single()
        if (row.first != run && row.second && row.third.isAfter(now.minus(staleAfter))) return@execute false
        // 시작 시각은 DB 시계다 - 색인 줄의 indexed_at 과 같은 시계라야 끝 정리의 비교가 인스턴스 시계 차이에 흔들리지 않는다
        jdbc.update(
            "update search_rebuilds set run_id = :run, running = true, started_at = $dbNow, updated_at = :now where doc_type = :t",
            mapOf("run" to run, "now" to dialect.instantParam(now), "t" to type),
        )
        queue.enqueue(SEARCH_REBUILD_JOB_TYPE, payload(type, run, null))
        true
    }!!

    override fun states(): Map<String, SearchRebuildState> {
        val fresh = time.now().minus(staleAfter)
        return jdbc.query("select doc_type, running, updated_at, finished_at from search_rebuilds", emptyMap<String, Any>()) { rs, _ ->
            rs.getString("doc_type") to SearchRebuildState(rs.getBoolean("running") && dialect.readInstant(rs, "updated_at")!!.isAfter(fresh), dialect.readInstant(rs, "finished_at"))
        }.toMap()
    }

    /** 이 실행이 아직 현재 실행이면 진행 표시를 갱신하고 시작 시각을 돌려준다. 다른 실행이 넘겨받았거나 끝났으면 null (옛 작업 줄은 조용히 멈춘다) */
    fun touch(type: String, run: String): Instant? {
        val updated = jdbc.update(
            "update search_rebuilds set updated_at = :now where doc_type = :t and run_id = :run and running = true",
            mapOf("now" to dialect.instantParam(time.now()), "t" to type, "run" to run),
        )
        if (updated == 0) return null
        return jdbc.query("select started_at from search_rebuilds where doc_type = :t", mapOf("t" to type)) { rs, _ -> dialect.readInstant(rs, "started_at") }.singleOrNull()
    }

    fun finish(type: String, run: String) {
        val now = dialect.instantParam(time.now())
        jdbc.update(
            "update search_rebuilds set running = false, updated_at = :now, finished_at = :now where doc_type = :t and run_id = :run",
            mapOf("now" to now, "t" to type, "run" to run),
        )
    }

    companion object {
        /** `{"type":"…","run":"…","after":"<base64url 커서>"}` - 커서는 소스가 정하는 아무 문자열이라 base64url 로 감싸 JSON 이스케이프를 피한다 */
        fun payload(type: String, run: String, after: String?): String {
            val cursor = after?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()
            return """{"type":"$type","run":"$run","after":"$cursor"}"""
        }

        private val PAYLOAD = Regex("""\{"type":"([a-z0-9._-]{1,32})","run":"([0-9a-f-]{36})","after":"([A-Za-z0-9_-]*)"}""")

        fun parse(payloadJson: String): RebuildPayload? = PAYLOAD.matchEntire(payloadJson)?.let {
            RebuildPayload(it.groupValues[1], it.groupValues[2], it.groupValues[3].takeIf { c -> c.isNotEmpty() }?.let { c -> String(Base64.getUrlDecoder().decode(c), Charsets.UTF_8) })
        }
    }

    data class RebuildPayload(val type: String, val run: String, val after: String?)
}
