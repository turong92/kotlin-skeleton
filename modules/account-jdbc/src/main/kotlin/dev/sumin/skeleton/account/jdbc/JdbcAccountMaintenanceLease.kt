package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.AccountMaintenanceLease
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Duration
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * `account_locks` 한 줄 임대(이름 · 만료 · 소유 표). 가져가기는 **조건부 UPDATE 한 문장**(`locked_until < now` 일 때만)이라 두 DB 에서 동시에 와도 하나만 이기고,
 * 줄이 아직 없으면 INSERT 의 유니크 위반이 진 쪽을 가린다. 시각은 앱의 [TimeProvider] 로 (인스턴스 사이 DB 시계에 기대지 않는다 — 임대는 분 단위다).
 */
class JdbcAccountMaintenanceLease(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
    private val time: TimeProvider,
) : AccountMaintenanceLease {
    override fun tryAcquire(name: String, ttl: Duration): String? {
        val now = time.now()
        val owner = UUID.randomUUID().toString()
        val p = MapSqlParameterSource().addValue("name", name).addValue("now", dialect.instantParam(now)).addValue("until", dialect.instantParam(now.plus(ttl))).addValue("owner", owner)
        if (jdbc.update("update account_locks set locked_until = :until, owner = :owner where name = :name and locked_until <= :now", p) == 1) return owner
        return try {
            if (jdbc.update("insert into account_locks (name, locked_until, owner) values (:name, :until, :owner)", p) == 1) owner else null
        } catch (_: DuplicateKeyException) {
            null   // 줄이 있고 아직 임대 중이거나, 방금 다른 인스턴스가 만들었다
        }
    }

    /** 소유 표가 지금의 것일 때만 푼다 — 임대가 만료돼 다른 인스턴스가 가져갔다면 이 해제는 아무것도 하지 않는다 */
    override fun release(name: String, owner: String) {
        jdbc.update(
            "update account_locks set locked_until = :now where name = :name and owner = :owner",
            MapSqlParameterSource().addValue("name", name).addValue("owner", owner).addValue("now", dialect.instantParam(time.now())),
        )
    }
}
