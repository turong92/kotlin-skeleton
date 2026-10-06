package dev.sumin.skeleton.alert.jdbc

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertRecorded
import dev.sumin.skeleton.alert.AlertSeverity
import dev.sumin.skeleton.alert.AlertStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

data class AlertRow(
    val id: Long,
    val kind: String,
    val key: String,
    val severity: String,
    val title: String,
    val detail: String,
    val firstAt: Instant,
    val occurredAt: Instant,
    val sentAt: Instant,
    val occurrences: Long,
    val suppressedCount: Int,
    val lastSuppressed: Int,
)

/**
 * `alerts` — (종류 · 키)마다 한 행. 접기 판정이 조건부 갱신(`update … where sent_at <= cutoff`)이라
 * 여러 스레드 · 인스턴스가 동시에 와도 행 잠금이 줄을 세운다 — 보내기는 하나. `UPDATE … RETURNING` 이 MySQL 에 없어 갱신 뒤 같은 트랜잭션에서 읽는다.
 * 기록은 부르는 쪽 트랜잭션과 따로(REQUIRES_NEW) — 이 문장의 실패가 그쪽을 멈추지 않는다.
 */
class JdbcAlertStore(
    private val jdbc: JdbcClient,
    transactions: org.springframework.transaction.PlatformTransactionManager,
    private val dialect: SqlDialect,
    private val retention: AlertJdbcProperties.Retention = AlertJdbcProperties.Retention(),
) : AlertStore {
    private val own = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    @Volatile private var lastPurge: Instant? = null

    override fun record(kind: AlertKind, key: String, severity: AlertSeverity, title: String, detail: String, now: Instant, minInterval: Duration): AlertRecorded {
        val recorded = recordOnce(kind.name, key, severity.name, title, detail, now, minInterval)
        purgeIfDue(now)
        return recorded
    }

    /**
     * ① 간격이 지난 기존 행을 갱신하면 이긴 호출 — 보낸다 ② 간격 안의 기존 행이면 접는다 ③ 행이 없으면 넣는다 — 넣은 쪽이 보낸다.
     * ③ 은 `insert` 가 유니크 위반을 던지는지로 판정한다: `SqlDialect.insertIgnore` 의 갱신 행 수는 MySQL 에서 이미 있는 행에도 1 이라 믿을 수 없다.
     * 동시에 처음 온 호출이 지면(위반) 처음부터 다시 — 이번엔 ① · ② 가 받는다.
     */
    private fun recordOnce(kind: String, key: String, severity: String, title: String, detail: String, now: Instant, minInterval: Duration): AlertRecorded {
        repeat(MAX_ROUNDS) {
            val existing = own.execute {
                // MySQL 은 SET 을 왼쪽부터 평가한다: last_suppressed = suppressed_count 가 suppressed_count = 0 보다 앞이어야 옛 값
                val won = jdbc.sql(
                    """update alerts
                       set severity = :severity, title = :title, detail = :detail, occurred_at = :now, occurrences = occurrences + 1,
                           last_suppressed = suppressed_count, suppressed_count = 0, sent_at = :now
                       where kind = :kind and alert_key = :key and sent_at <= :cutoff""",
                ).param("severity", severity).param("title", title).param("detail", detail).param("now", dialect.instantParam(now))
                    .param("kind", kind).param("key", key).param("cutoff", dialect.instantParam(now.minus(minInterval))).update()
                if (won == 1) {
                    val row = counters(kind, key)
                    return@execute AlertRecorded(send = true, suppressedFolded = row.lastSuppressed, occurrences = row.occurrences)
                }
                val folded = jdbc.sql(
                    """update alerts
                       set severity = :severity, title = :title, detail = :detail, occurred_at = :now,
                           occurrences = occurrences + 1, suppressed_count = suppressed_count + 1
                       where kind = :kind and alert_key = :key""",
                ).param("severity", severity).param("title", title).param("detail", detail).param("now", dialect.instantParam(now))
                    .param("kind", kind).param("key", key).update()
                if (folded == 1) AlertRecorded(send = false, suppressedFolded = 0, occurrences = counters(kind, key).occurrences) else null
            }
            if (existing != null) return existing
            try {
                own.execute {
                    jdbc.sql(
                        """insert into alerts (kind, alert_key, severity, title, detail, first_at, occurred_at, sent_at)
                           values (:kind, :key, :severity, :title, :detail, :now, :now, :now)""",
                    ).param("kind", kind).param("key", key).param("severity", severity).param("title", title).param("detail", detail)
                        .param("now", dialect.instantParam(now)).update()
                }
                return AlertRecorded(send = true, suppressedFolded = 0, occurrences = 1)
            } catch (e: DuplicateKeyException) {
                // 동시에 온 다른 호출이 먼저 넣었다 — 처음부터 다시
            }
        }
        error("could not record alert $kind after $MAX_ROUNDS rounds")
    }

    private fun counters(kind: String, key: String): AlertRow = find(kind, key) ?: error("alert row vanished inside its own transaction: $kind")

    fun find(kind: String, key: String): AlertRow? =
        jdbc.sql("select * from alerts where kind = :kind and alert_key = :key").param("kind", kind).param("key", key)
            .query(::map).optional().orElse(null)

    /** 가장 최근에 온 것부터 */
    fun recent(limit: Int): List<AlertRow> =
        jdbc.sql("select * from alerts order by occurred_at desc, id desc limit :limit").param("limit", limit).query(::map).list()

    /** 마지막으로 온 때가 [before] 보다 오래된 줄을 지운다. 지운 수를 돌려준다 */
    fun purgeOlderThan(before: Instant): Int =
        jdbc.sql("delete from alerts where occurred_at < :before").param("before", dialect.instantParam(before)).update()

    private fun purgeIfDue(now: Instant) {
        if (!retention.enabled) return
        val last = lastPurge
        if (last != null && now.isBefore(last.plus(retention.interval))) return
        lastPurge = now
        runCatching { purgeOlderThan(now.minus(retention.keep)) } // 정리가 실패해도 기록은 이미 끝났다
    }

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = AlertRow(
        id = rs.getLong("id"), kind = rs.getString("kind"), key = rs.getString("alert_key"), severity = rs.getString("severity"),
        title = rs.getString("title"), detail = rs.getString("detail"),
        firstAt = dialect.readInstant(rs, "first_at")!!, occurredAt = dialect.readInstant(rs, "occurred_at")!!, sentAt = dialect.readInstant(rs, "sent_at")!!,
        occurrences = rs.getLong("occurrences"), suppressedCount = rs.getInt("suppressed_count"), lastSuppressed = rs.getInt("last_suppressed"),
    )

    private companion object {
        const val MAX_ROUNDS = 3
    }
}
