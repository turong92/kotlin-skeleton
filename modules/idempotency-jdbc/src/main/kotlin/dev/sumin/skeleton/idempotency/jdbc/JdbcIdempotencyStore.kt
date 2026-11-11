package dev.sumin.skeleton.idempotency.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.idempotency.IdempotencyRequest
import dev.sumin.skeleton.idempotency.IdempotencyReservation
import dev.sumin.skeleton.idempotency.IdempotencyStore
import dev.sumin.skeleton.idempotency.StoredIdempotencyResponse
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.Types
import java.util.UUID
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * 표 하나(`idempotency_keys`, 기본 키 = 범위가 붙은 키). 여러 인스턴스가 같은 키로 동시에 와도 **insert-ignore 하나가 이긴다** -
 * 예약마다 난수(`claim`)를 같이 넣고 다시 읽어 내 것이면 [IdempotencyReservation.Started], 남의 것이면 지문을 비교해 충돌 · 진행 중 · 재생으로 나눈다.
 * 만료한 줄은 같은 키의 예약이 먼저 지우고, 나머지는 [purgeExpired] 가 지운다. 응답이 저장되기 전에 프로세스가 죽으면 그 키는 [inProgressLease] 동안만 「진행 중」(409)이고, 그 뒤 첫 예약이 새로 시작한다.
 */
class JdbcIdempotencyStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
    private val time: TimeProvider,
    /** 응답을 저장하기 전의 「진행 중」 예약이 유효한 시간 - 지나면 같은 키의 예약이 새로 시작한다(요청 도중 프로세스가 죽어도 키가 TTL 내내 묶이지 않게) */
    private val inProgressLease: java.time.Duration = java.time.Duration.ofMinutes(5),
    private val json: ObjectMapper = ObjectMapper(),
) : IdempotencyStore {
    private val insertIgnore = dialect.insertIgnore("idempotency_keys", INSERT_COLUMNS, listOf("scoped_key"))

    override fun reserve(request: IdempotencyRequest): IdempotencyReservation {
        val now = time.now()
        val claim = UUID.randomUUID().toString()
        // 지난 줄이 이 키를 막지 않게 - 같은 키의 만료 줄, 그리고 임대가 지난 「진행 중」 줄(응답을 못 저장하고 죽은 요청)만 지운다
        jdbc.update(
            "delete from idempotency_keys where scoped_key = :k and (expires_at <= :now or (state = :progress and created_at <= :lease_cut))",
            MapSqlParameterSource("k", request.scopedKey)
                .addValue("now", dialect.instantParam(now))
                .addValue("progress", IN_PROGRESS)
                .addValue("lease_cut", dialect.instantParam(now.minus(inProgressLease))),
        )
        repeat(RETRIES) {
            jdbc.update(
                insertIgnore,
                MapSqlParameterSource()
                    .addValue("scoped_key", request.scopedKey)
                    .addValue("fingerprint", request.fingerprint)
                    .addValue("state", IN_PROGRESS)
                    .addValue("claim", claim)
                    .addValue("expires_at", dialect.instantParam(request.expiresAt))
                    .addValue("created_at", dialect.instantParam(now)),
            )
            val row = jdbc.query("select * from idempotency_keys where scoped_key = :k", mapOf("k" to request.scopedKey)) { rs, _ ->
                Row(rs.getString("fingerprint"), rs.getString("state"), rs.getString("claim"), rs.getInt("status"), rs.getString("content_type"), rs.getString("headers"), rs.getBytes("body"))
            }.firstOrNull() ?: return@repeat   // 읽기 전에 청소가 지웠다 - 다시 시도
            return when {
                row.claim == claim -> IdempotencyReservation.Started(request)
                row.fingerprint != request.fingerprint -> IdempotencyReservation.Conflict
                row.state == COMPLETED -> IdempotencyReservation.Replay(row.toResponse())
                else -> IdempotencyReservation.InProgress
            }
        }
        return IdempotencyReservation.InProgress
    }

    // 예약을 가린 claim 은 보지 않는다(포트가 scopedKey 만 준다) - 임대를 넘겨 돈 요청이 끝나면, 그사이 같은 키로 새로 예약한 요청의 줄을 자기 응답으로 덮을 수 있다
    override fun complete(scopedKey: String, response: StoredIdempotencyResponse) {
        jdbc.update(
            "update idempotency_keys set state = :done, status = :status, content_type = :content_type, headers = :headers, body = :body where scoped_key = :k and state = :progress",
            MapSqlParameterSource()
                .addValue("done", COMPLETED)
                .addValue("progress", IN_PROGRESS)
                .addValue("status", response.status)
                .addValue("content_type", response.contentType)
                .addValue("headers", json.writeValueAsString(response.headers))
                .addValue("body", response.body, Types.VARBINARY)
                .addValue("k", scopedKey),
        )
    }

    override fun release(scopedKey: String) {
        jdbc.update("delete from idempotency_keys where scoped_key = :k", mapOf("k" to scopedKey))
    }

    /** 만료한 줄을 모두 지운다 - 지운 수 */
    fun purgeExpired(): Int =
        jdbc.update("delete from idempotency_keys where expires_at <= :now", mapOf("now" to dialect.instantParam(time.now())))

    private inner class Row(
        val fingerprint: String,
        val state: String,
        val claim: String,
        val status: Int,
        val contentType: String?,
        val headers: String?,
        val body: ByteArray?,
    ) {
        fun toResponse() = StoredIdempotencyResponse(
            status = status,
            contentType = contentType,
            headers = headers?.let { json.readValue(it, HEADERS) } ?: emptyMap(),
            body = body ?: ByteArray(0),
        )
    }

    private companion object {
        const val IN_PROGRESS = "IN_PROGRESS"
        const val COMPLETED = "COMPLETED"
        const val RETRIES = 3
        val INSERT_COLUMNS = listOf("scoped_key", "fingerprint", "state", "claim", "expires_at", "created_at")
        val HEADERS = object : TypeReference<Map<String, List<String>>>() {}
    }
}
