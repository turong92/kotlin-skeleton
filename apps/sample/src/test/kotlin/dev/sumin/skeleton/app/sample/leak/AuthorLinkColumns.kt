package dev.sumin.skeleton.app.sample.leak

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 스펙 §12 7 — `board*` · `notification*` · `author_profiles` 의 **모든 열**을 공개 / 비공개로 나눈 표. 새 열이 생기면 이 표에 적어야 시험이 통과한다
 * (새 열이 조용히 계정 id 를 담지 못하게). 두 방언이 같은 표를 쓴다.
 *
 * - 비공개: 계정 id(또는 탈퇴 뒤 톰스톤)를 담을 수 있는 열 — 어떤 응답에도 나가지 않는다.
 * - 공개: 응답이나 남의 편지함에 오를 수 있는 열 — 계정 id · 이메일 · 톰스톤 · 계정 이름이 없어야 하고, 작성자별 값끼리 겹치지 않아야 한다.
 */
object AuthorLinkColumns {
    val PRIVATE: Map<String, Set<String>> = mapOf(
        "board_posts" to setOf("author_id"),
        "board_comments" to setOf("author_id"),
        "board_reactions" to setOf("account_id"),
        "notification_inbox" to setOf("recipient_id", "recipient_ids_json"),
        "author_profiles" to setOf("account_id", "primary_of"),
    )

    val PUBLIC: Map<String, Set<String>> = mapOf(
        "boards" to setOf("code", "name", "description", "created_at"),
        "board_posts" to setOf(
            "id", "board_code", "title", "body", "status", "pinned", "view_count", "comment_count", "reaction_count", "attachment_count",
            "created_at", "updated_at", "author_ref",
        ),
        "board_post_attachments" to setOf("post_id", "sort_order", "storage_key"),
        "board_comments" to setOf("id", "post_id", "parent_id", "root_id", "depth", "body", "status", "reaction_count", "created_at", "updated_at", "author_ref"),
        "board_reactions" to setOf("target_type", "target_id", "reaction_type", "created_at"),
        "notification_inbox" to setOf(
            "event_id", "topic", "type", "severity", "title", "message", "payload_json", "event_created_at", "read_at", "created_at", "updated_at",
        ),
        "author_profiles" to setOf("id", "name", "name_key", "tag", "status", "adopted_at", "created_at", "updated_at", "deleted_at"),
    )

    private fun flat(map: Map<String, Set<String>>) = map.flatMap { (table, columns) -> columns.map { "$table.$it" } }.toSet()

    /** [actual] = `information_schema` 에서 읽은 `표.열`. 분류되지 않은 열이 있거나(새 열) 표에만 있는 열(사라진 열)이 있으면 사유를 돌려준다 */
    fun problems(actual: Set<String>): List<String> {
        val classified = flat(PRIVATE) + flat(PUBLIC)
        val both = flat(PRIVATE) intersect flat(PUBLIC)
        return actual.minus(classified).sorted().map { "분류되지 않은 열: $it (AuthorLinkColumns 의 PUBLIC 또는 PRIVATE 에 적는다)" } +
            classified.minus(actual).sorted().map { "표에는 있는데 DB 에 없는 열: $it" } +
            both.sorted().map { "공개와 비공개 양쪽에 적힌 열: $it" }
    }

    /** 이 표들의 열 — 현재 스키마(PostgreSQL `current_schema()` · MySQL `database()`) */
    fun columnsOf(jdbc: JdbcClient, schemaExpr: String): Set<String> =
        jdbc.sql(
            "select table_name, column_name from information_schema.columns where table_schema = $schemaExpr " +
                "and (table_name like 'board%' or table_name like 'notification%' or table_name = 'author_profiles')",
        ).query { rs, _ -> rs.getString(1).lowercase() + "." + rs.getString(2).lowercase() }.list().toSet()
}
