package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 글 · 댓글 · 고친 판에 붙어 있는 저장 키 - storage 의 비회원 업로드 회수가 「붙은 것은 지우지 않는다」를 지키려고 묻는다 */
class JdbcUploadedKeyUsage(private val jdbc: NamedParameterJdbcTemplate) : UploadedKeyUsage {
    override fun inUse(keys: Collection<String>): Set<String> {
        if (keys.isEmpty()) return emptySet()
        val params = MapSqlParameterSource("keys", keys.toList())
        return TABLES.flatMap { table -> jdbc.queryForList("select storage_key from $table where storage_key in (:keys)", params, String::class.java) }.filterNotNull().toSet()
    }

    private companion object {
        val TABLES = listOf("board_post_attachments", "board_comment_attachments", "board_revision_attachments")
    }
}
