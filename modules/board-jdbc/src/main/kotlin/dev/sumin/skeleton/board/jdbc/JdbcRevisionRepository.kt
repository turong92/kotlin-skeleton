package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.BoardRevisionRepository
import dev.sumin.skeleton.board.PageResult
import dev.sumin.skeleton.board.Revision
import dev.sumin.skeleton.board.RevisionTarget
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
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
) : BoardRevisionRepository {
    override fun submit(revision: Revision): Boolean =
        tx.execute {
            lockTarget(revision.target, revision.targetId, revision.postId)
            val replaced = discardRevision(jdbc, revision.target, revision.targetId) > 0
            jdbc.update(
                """
                insert into board_revisions (target_type, target_id, board_code, post_id, title, body, attachment_count, created_at, updated_at)
                values (:t, :id, :board, :post, :title, :body, :count, :now, :now)
                """.trimIndent(),
                MapSqlParameterSource().addValue("t", revision.target.name).addValue("id", revision.targetId).addValue("board", revision.boardCode)
                    .addValue("post", revision.postId).addValue("title", revision.title).addValue("body", revision.body)
                    .addValue("count", revision.attachments.size).addValue("now", dialect.instantParam(revision.updatedAt)),
            )
            if (revision.attachments.isNotEmpty()) {
                jdbc.batchUpdate(
                    "insert into board_revision_attachments (target_type, target_id, sort_order, storage_key) values (:t, :id, :order, :key)",
                    revision.attachments.mapIndexed { i, key ->
                        MapSqlParameterSource().addValue("t", revision.target.name).addValue("id", revision.targetId).addValue("order", i).addValue("key", key)
                    }.toTypedArray(),
                )
            }
            replaced
        }!!

    override fun find(target: RevisionTarget, ids: Collection<Long>): Map<Long, Revision> {
        if (ids.isEmpty()) return emptyMap()
        val rows = jdbc.query(
            "select * from board_revisions where target_type = :t and target_id in (:ids)", mapOf("t" to target.name, "ids" to ids),
        ) { rs, _ -> rs.revision(target) }
        return withAttachments(target, rows).associateBy { it.targetId }
    }

    override fun delete(target: RevisionTarget, id: Long): Boolean = tx.execute { discardRevision(jdbc, target, id) > 0 }!!

    override fun apply(target: RevisionTarget, id: Long, now: Instant): Boolean {
        // 글 id 는 바뀌지 않는 값이라 트랜잭션 밖에서 읽는다 — 안에서 먼저 읽으면 MySQL(REPEATABLE READ)의 스냅샷이 잠금 전에 잡혀, 기다린 뒤에도 옛 판이 보인다
        val postId = (if (target == RevisionTarget.POST) id else jdbc.queryForList(
            "select post_id from board_comments where id = :id", mapOf("id" to id), Long::class.java,
        ).firstOrNull()) ?: return false
        return tx.execute {
            lockTarget(target, id, postId)
            // 잠근 뒤에 처음 읽는다 — 기다리는 사이 덮어써졌거나 지워졌을 수 있다
            val rev = find(target, listOf(id))[id] ?: return@execute false
            val table = if (target == RevisionTarget.POST) "board_posts" else "board_comments"
            val status = jdbc.queryForList("select status from $table where id = :id", mapOf("id" to id), String::class.java).firstOrNull()
            if (status != "PUBLISHED") {
                discardRevision(jdbc, target, id)
                return@execute false
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
                jdbc.update("update board_comments set body = :body, updated_at = :now where id = :id", params)
            }
            discardRevision(jdbc, target, id)
            true
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
