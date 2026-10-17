package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.BoardRevisionRepository
import dev.sumin.skeleton.board.PageResult
import dev.sumin.skeleton.board.Revision
import dev.sumin.skeleton.board.RevisionSubmit
import dev.sumin.skeleton.board.RevisionWrite
import dev.sumin.skeleton.board.sameVersion
import dev.sumin.skeleton.board.RevisionTarget
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * 고친 판. 잠금 순서는 글 → 댓글이다 (판 만들기 · 승인 모두 같은 순서 — 교착 없음). 판을 덮어쓰는 것과 승인이 동시에 와도 대상 행 잠금이 줄을 세워
 * 어느 한쪽이 먼저 끝난 상태만 보인다: 승인이 먼저면 새 판이 새로 생기고, 덮어쓰기가 먼저면 승인이 덮어쓴 판을 옮긴다.
 */
class JdbcBoardRevisionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
    private val publicQueue: PublicQueueWriter? = null,
) : BoardRevisionRepository {
    override fun submit(revision: Revision): RevisionSubmit =
        tx.execute {
            lockTarget(revision.target, revision.targetId, revision.postId)
            // 잠근 뒤에 처음 읽는다 - 상태 변경과 겹쳐 가려진 대상에 판이 남지 않게 (apply 와 같은 모양)
            val table = if (revision.target == RevisionTarget.POST) "board_posts" else "board_comments"
            val status = jdbc.queryForList("select status from $table where id = :id", mapOf("id" to revision.targetId), String::class.java).firstOrNull()
            if (status != "PUBLISHED") return@execute RevisionSubmit.TARGET_GONE
            // 판의 버전 = updated_at - 덮어쓸 때마다 반드시 커진다(같은 마이크로초에 덮어써도 결정하는 쪽이 바뀐 것을 알아본다)
            val previous = find(revision.target, listOf(revision.targetId))[revision.targetId]?.updatedAt
            val stamp = if (previous != null && !revision.updatedAt.isAfter(previous)) previous.plus(1, ChronoUnit.MICROS) else revision.updatedAt
            val replaced = discardRevision(jdbc, revision.target, revision.targetId) > 0
            jdbc.update(
                """
                insert into board_revisions (target_type, target_id, board_code, post_id, title, body, attachment_count, created_at, updated_at)
                values (:t, :id, :board, :post, :title, :body, :count, :now, :now)
                """.trimIndent(),
                MapSqlParameterSource().addValue("t", revision.target.name).addValue("id", revision.targetId).addValue("board", revision.boardCode)
                    .addValue("post", revision.postId).addValue("title", revision.title).addValue("body", revision.body)
                    .addValue("count", revision.attachments.size).addValue("now", dialect.instantParam(stamp)),
            )
            if (revision.attachments.isNotEmpty()) {
                jdbc.batchUpdate(
                    "insert into board_revision_attachments (target_type, target_id, sort_order, storage_key) values (:t, :id, :order, :key)",
                    revision.attachments.mapIndexed { i, key ->
                        MapSqlParameterSource().addValue("t", revision.target.name).addValue("id", revision.targetId).addValue("order", i).addValue("key", key)
                    }.toTypedArray(),
                )
            }
            if (replaced) RevisionSubmit.REPLACED else RevisionSubmit.CREATED
        }!!

    override fun find(target: RevisionTarget, ids: Collection<Long>): Map<Long, Revision> {
        if (ids.isEmpty()) return emptyMap()
        val rows = jdbc.query(
            "select * from board_revisions where target_type = :t and target_id in (:ids)", mapOf("t" to target.name, "ids" to ids),
        ) { rs, _ -> rs.revision(target) }
        return withAttachments(target, rows).associateBy { it.targetId }
    }

    override fun delete(target: RevisionTarget, id: Long): Boolean = tx.execute { discardRevision(jdbc, target, id) > 0 }!!

    override fun reject(target: RevisionTarget, id: Long, expectedVersion: Instant): RevisionWrite {
        val postId = postIdOf(target, id) ?: return RevisionWrite.NONE
        return tx.execute {
            lockTarget(target, id, postId)
            val rev = find(target, listOf(id))[id] ?: return@execute RevisionWrite.NONE
            if (!rev.sameVersion(expectedVersion)) return@execute RevisionWrite.CHANGED
            discardRevision(jdbc, target, id)
            RevisionWrite.DONE
        }!!
    }

    private fun postIdOf(target: RevisionTarget, id: Long): Long? =
        if (target == RevisionTarget.POST) id
        else jdbc.queryForList("select post_id from board_comments where id = :id", mapOf("id" to id), Long::class.java).firstOrNull()

    override fun apply(target: RevisionTarget, id: Long, now: Instant, expectedVersion: Instant): RevisionWrite {
        // 글 id 는 바뀌지 않는 값이라 트랜잭션 밖에서 읽는다 — 안에서 먼저 읽으면 MySQL(REPEATABLE READ)의 스냅샷이 잠금 전에 잡혀, 기다린 뒤에도 옛 판이 보인다
        val postId = postIdOf(target, id) ?: return RevisionWrite.NONE
        return tx.execute {
            lockTarget(target, id, postId)
            // 잠근 뒤에 처음 읽는다 — 기다리는 사이 덮어써졌거나 지워졌을 수 있다
            val rev = find(target, listOf(id))[id] ?: return@execute RevisionWrite.NONE
            // 운영자가 본 판이 아니다(그 사이 작성자가 덮어썼다) - 다른 판을 공개하지 않는다
            if (!rev.sameVersion(expectedVersion)) return@execute RevisionWrite.CHANGED
            val table = if (target == RevisionTarget.POST) "board_posts" else "board_comments"
            val status = jdbc.queryForList("select status from $table where id = :id", mapOf("id" to id), String::class.java).firstOrNull()
            if (status != "PUBLISHED") {
                discardRevision(jdbc, target, id)
                return@execute RevisionWrite.NONE
            }
            val params = MapSqlParameterSource().addValue("id", id).addValue("body", rev.body).addValue("now", dialect.instantParam(now))
                .addValue("count", rev.attachments.size)
            if (target == RevisionTarget.POST) {
                jdbc.update("update board_posts set title = :title, body = :body, attachment_count = :count, updated_at = :now where id = :id", params.addValue("title", rev.title))
                jdbc.update("delete from board_post_attachments where post_id = :id", params)
                rev.attachments.forEachIndexed { i, key ->
                    jdbc.update("insert into board_post_attachments (post_id, sort_order, storage_key) values (:id, :order, :key)", MapSqlParameterSource().addValue("id", id).addValue("order", i).addValue("key", key))
                }
            } else {
                jdbc.update("update board_comments set body = :body, attachment_count = :count, updated_at = :now where id = :id", params)
                jdbc.update("delete from board_comment_attachments where comment_id = :id", params)
                rev.attachments.forEachIndexed { i, key ->
                    jdbc.update("insert into board_comment_attachments (comment_id, sort_order, storage_key) values (:id, :order, :key)", MapSqlParameterSource().addValue("id", id).addValue("order", i).addValue("key", key))
                }
            }
            discardRevision(jdbc, target, id)
            publicQueue?.enqueue(target, id, now)   // 판 승인으로 첨부가 바뀌었다 - 같은 트랜잭션에서 큐에 적는다
            RevisionWrite.DONE
        }!!
    }

    override fun pending(target: RevisionTarget, boardCode: String?, postId: Long?, page: Int, size: Int): PageResult<Revision> {
        val where = if (target == RevisionTarget.POST) "board_code = :scope" else "post_id = :scope"
        val params = MapSqlParameterSource().addValue("t", target.name).addValue("scope", if (target == RevisionTarget.POST) boardCode else postId)
        val total = jdbc.queryForObject("select count(*) from board_revisions where target_type = :t and $where", params, Long::class.java) ?: 0L
        params.addValue("limit", size).addValue("offset", page * size)
        val rows = jdbc.query(
            "select * from board_revisions where target_type = :t and $where order by updated_at, target_id limit :limit offset :offset", params,
        ) { rs, _ -> rs.revision(target) }
        return PageResult(withAttachments(target, rows), total)
    }

    private fun lockTarget(target: RevisionTarget, id: Long, postId: Long) {
        jdbc.queryForList("select id from board_posts where id = :id for update", mapOf("id" to postId), Long::class.java)
        if (target == RevisionTarget.COMMENT) jdbc.queryForList("select id from board_comments where id = :id for update", mapOf("id" to id), Long::class.java)
    }

    private fun withAttachments(target: RevisionTarget, rows: List<Revision>): List<Revision> {
        if (rows.isEmpty()) return rows
        val keys = linkedMapOf<Long, MutableList<String>>()
        jdbc.query(
            "select target_id, storage_key from board_revision_attachments where target_type = :t and target_id in (:ids) order by target_id, sort_order",
            mapOf("t" to target.name, "ids" to rows.map { it.targetId }),
        ) { rs -> keys.getOrPut(rs.getLong("target_id")) { mutableListOf() } += rs.getString("storage_key") }
        return rows.map { it.copy(attachments = keys[it.targetId].orEmpty()) }
    }

    private fun ResultSet.revision(target: RevisionTarget) =
        Revision(
            target, getLong("target_id"), getString("board_code"), getLong("post_id"), getString("title"), getString("body"), emptyList(),
            instant(dialect, "created_at"), instant(dialect, "updated_at"),
        )
}
