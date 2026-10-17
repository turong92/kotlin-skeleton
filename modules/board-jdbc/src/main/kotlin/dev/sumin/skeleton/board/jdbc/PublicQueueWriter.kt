package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.RevisionTarget
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 공개 여부에 영향을 주는 쓰기가 **같은 트랜잭션 안에서** 공개 영역 조정 큐(`board_public_attachment_queue`)에 대상 한 줄을 적는다(이미 있으면 그대로).
 * 커밋 뒤 리스너가 맞추고 줄을 지우고, 리스너가 돌기 전에 프로세스가 죽어도 매분 조정이 남은 줄을 맞춘다(아웃박스).
 * [enabled] 가 거짓이면(공개 영역이 꺼진 기본 설정) 아무것도 적지 않아 쓰기 쿼리가 늘지 않는다.
 */
class PublicQueueWriter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
    private val enabled: () -> Boolean,
) {
    /**
     * 줄이 없으면 만들고(번호 0) **언제나** 번호를 +1 한다 - 맞추는 중에 새로 들어온 쓰기를 맞춘 쪽이 지우지 않게(지울 때 읽은 번호와 같은지 본다).
     * 앱 시각에 기대지 않고, 쓰기마다 정확히 한 번 오른다(동시에 둘이 처음 써도 행 잠금이 줄을 세운다).
     */
    fun enqueue(target: RevisionTarget, id: Long, now: Instant) {
        if (!enabled()) return
        val params = MapSqlParameterSource().addValue("target_type", target.name).addValue("target_id", id).addValue("queued_at", dialect.instantParam(now)).addValue("version", 0L)
        jdbc.update(dialect.insertIgnore("board_public_attachment_queue", listOf("target_type", "target_id", "queued_at", "version"), listOf("target_type", "target_id")), params)
        jdbc.update("update board_public_attachment_queue set queued_at = :queued_at, version = version + 1, attempts = 0, next_attempt_at = null where target_type = :target_type and target_id = :target_id", params)
    }
}
