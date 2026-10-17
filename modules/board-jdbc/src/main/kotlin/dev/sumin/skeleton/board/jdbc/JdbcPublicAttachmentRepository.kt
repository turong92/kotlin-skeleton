package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.BoardPublicAttachmentRepository
import dev.sumin.skeleton.board.PublicCopy
import dev.sumin.skeleton.board.RevisionTarget
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.security.MessageDigest
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 공개 영역 조정자의 저장소. 공개 조건: 글 PUBLISHED, 댓글 PUBLISHED + 그 글 PUBLISHED. 고친 판의 첨부 표(board_revision_attachments)는 어디에서도 읽지 않는다.
 * 줄의 키는 sha256(storage_key) - 긴 키를 기본 키에 넣지 않으려고.
 */
class JdbcPublicAttachmentRepository(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : BoardPublicAttachmentRepository {
    override fun eligible(target: RevisionTarget, id: Long): List<String> =
        when (target) {
            RevisionTarget.POST -> jdbc.queryForList(
                "select a.storage_key from board_post_attachments a join board_posts p on p.id = a.post_id where a.post_id = :id and p.status = 'PUBLISHED' order by a.sort_order",
                mapOf("id" to id), String::class.java,
            ).filterNotNull()
            RevisionTarget.COMMENT -> jdbc.queryForList(
                """
                select a.storage_key from board_comment_attachments a
                join board_comments c on c.id = a.comment_id join board_posts p on p.id = c.post_id
                where a.comment_id = :id and c.status = 'PUBLISHED' and p.status = 'PUBLISHED' order by a.sort_order
                """.trimIndent(),
                mapOf("id" to id), String::class.java,
            ).filterNotNull()
        }

    override fun copied(target: RevisionTarget, id: Long): List<String> =
        jdbc.queryForList("select storage_key from board_public_attachments where target_type = :t and target_id = :id", mapOf("t" to target.name, "id" to id), String::class.java).filterNotNull()

    override fun mark(copy: PublicCopy, now: Instant) {
        val exists = jdbc.queryForObject(
            "select count(*) from board_public_attachments where target_type = :t and target_id = :id and key_hash = :h",
            params(copy), Long::class.java,
        ) ?: 0L
        if (exists > 0) return
        jdbc.update(
            "insert into board_public_attachments (target_type, target_id, key_hash, storage_key, created_at) values (:t, :id, :h, :k, :now)",
            params(copy).addValue("k", copy.key).addValue("now", dialect.instantParam(now)),
        )
    }

    override fun unmark(copy: PublicCopy) {
        jdbc.update("delete from board_public_attachments where target_type = :t and target_id = :id and key_hash = :h", params(copy))
    }

    override fun heldElsewhere(copy: PublicCopy): Boolean =
        (jdbc.queryForObject(
            "select count(*) from board_public_attachments where key_hash = :h and not (target_type = :t and target_id = :id)",
            params(copy), Long::class.java,
        ) ?: 0L) > 0

    override fun commentsWithCopies(postId: Long): List<Long> =
        jdbc.queryForList(
            "select distinct c.target_id from board_public_attachments c join board_comments m on m.id = c.target_id where c.target_type = 'COMMENT' and m.post_id = :id",
            mapOf("id" to postId), Long::class.java,
        ).filterNotNull()

    override fun stale(limit: Int): List<PublicCopy> {
        val posts = jdbc.query(
            """
            select c.target_id, c.storage_key from board_public_attachments c where c.target_type = 'POST' and not exists (
                select 1 from board_post_attachments a join board_posts p on p.id = a.post_id
                where a.post_id = c.target_id and a.storage_key = c.storage_key and p.status = 'PUBLISHED')
            order by c.created_at limit :limit
            """.trimIndent(),
            mapOf("limit" to limit),
        ) { rs, _ -> PublicCopy(RevisionTarget.POST, rs.getLong(1), rs.getString(2)) }
        val comments = jdbc.query(
            """
            select c.target_id, c.storage_key from board_public_attachments c where c.target_type = 'COMMENT' and not exists (
                select 1 from board_comment_attachments a join board_comments m on m.id = a.comment_id join board_posts p on p.id = m.post_id
                where a.comment_id = c.target_id and a.storage_key = c.storage_key and m.status = 'PUBLISHED' and p.status = 'PUBLISHED')
            order by c.created_at limit :limit
            """.trimIndent(),
            mapOf("limit" to limit),
        ) { rs, _ -> PublicCopy(RevisionTarget.COMMENT, rs.getLong(1), rs.getString(2)) }
        return posts + comments
    }

    override fun missing(limit: Int): List<PublicCopy> {
        val posts = jdbc.query(
            """
            select a.post_id, a.storage_key from board_post_attachments a join board_posts p on p.id = a.post_id
            where p.status = 'PUBLISHED' and not exists (
                select 1 from board_public_attachments c where c.target_type = 'POST' and c.target_id = a.post_id and c.storage_key = a.storage_key)
            limit :limit
            """.trimIndent(),
            mapOf("limit" to limit),
        ) { rs, _ -> PublicCopy(RevisionTarget.POST, rs.getLong(1), rs.getString(2)) }
        val comments = jdbc.query(
            """
            select a.comment_id, a.storage_key from board_comment_attachments a
            join board_comments m on m.id = a.comment_id join board_posts p on p.id = m.post_id
            where m.status = 'PUBLISHED' and p.status = 'PUBLISHED' and not exists (
                select 1 from board_public_attachments c where c.target_type = 'COMMENT' and c.target_id = a.comment_id and c.storage_key = a.storage_key)
            limit :limit
            """.trimIndent(),
            mapOf("limit" to limit),
        ) { rs, _ -> PublicCopy(RevisionTarget.COMMENT, rs.getLong(1), rs.getString(2)) }
        return posts + comments
    }

    private fun params(copy: PublicCopy) = MapSqlParameterSource().addValue("t", copy.target.name).addValue("id", copy.targetId).addValue("h", hash(copy.key))

    private fun hash(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
