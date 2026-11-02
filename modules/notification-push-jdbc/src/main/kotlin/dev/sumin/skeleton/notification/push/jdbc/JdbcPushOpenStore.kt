package dev.sumin.skeleton.notification.push.jdbc

import dev.sumin.skeleton.notification.push.PushOpenStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 표 `push_opens` - (이벤트, 받는 사람) 쌍당 한 줄. 첫 기록인지는 **기본 키가 정한다**: 그냥 넣어 보고 중복 키 오류면 이미 있던 것이다 - 동시에 온 두 보고 중 하나만 성공한다.
 * (`insertIgnore` 의 갱신 행 수는 MySQL 에서 믿을 수 없다 - [SqlDialect.insertIgnore].) 오류를 잡는 방식이라 **트랜잭션 밖에서** 부른다(PostgreSQL 은 트랜잭션 안의 중복 키 오류로 트랜잭션 전체가 죽는다) - 보고 경로가 그렇다.
 */
class JdbcPushOpenStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
) : PushOpenStore {
    override fun recordFirst(eventId: String, recipientId: String, at: Instant): Boolean =
        try {
            jdbc.update(
                "insert into push_opens (event_id, recipient_id, opened_at) values (:event_id, :recipient_id, :opened_at)",
                MapSqlParameterSource().addValue("event_id", eventId).addValue("recipient_id", recipientId).addValue("opened_at", dialect.instantParam(at)),
            )
            true
        } catch (_: DuplicateKeyException) {
            false
        }
}
