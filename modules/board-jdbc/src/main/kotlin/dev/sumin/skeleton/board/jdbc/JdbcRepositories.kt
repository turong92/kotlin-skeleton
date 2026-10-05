package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.Board
import dev.sumin.skeleton.board.BoardRepository
import dev.sumin.skeleton.board.Comment
import dev.sumin.skeleton.board.CommentRepository
import dev.sumin.skeleton.board.CommentRootQuery
import dev.sumin.skeleton.board.CommentSort
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.NewPost
import dev.sumin.skeleton.board.PageResult
import dev.sumin.skeleton.board.Post
import dev.sumin.skeleton.board.PostChange
import dev.sumin.skeleton.board.PostListItem
import dev.sumin.skeleton.board.PostQuery
import dev.sumin.skeleton.board.PostRepository
import dev.sumin.skeleton.board.PostSort
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.ReactionMode
import dev.sumin.skeleton.board.ReactionRepository
import dev.sumin.skeleton.board.ReactionTarget
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.transaction.support.TransactionTemplate

/*
 * 동시성 규칙 (docs/modules/board.md "카운터와 반응 규칙"):
 *  - 카운터(comment_count · reaction_count · view_count)는 항상 `set x = x + :delta` 원자적 UPDATE 이고, 바꾸는 행과 같은 트랜잭션이다.
 *  - 행을 잠그는 순서는 글 → 댓글 하나뿐이다. 반응은 대상 행(글 또는 댓글) 하나만 잠근다. 그래서 교착이 없다.
 *  - 댓글 삽입은 삽입 전에 글의 카운터를 올린다 (InnoDB 는 FK 검사가 부모 행에 공유 락을 걸어, 삽입 뒤에 UPDATE 하면 락 업그레이드 교착이 난다).
 */

internal fun ResultSet.instant(dialect: SqlDialect, column: String): Instant = dialect.readInstant(this, column)!!

internal fun long(rs: ResultSet, column: String): Long = rs.getLong(column)

class JdbcBoardRepository(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : BoardRepository {
    override fun findAll(): List<Board> =
        jdbc.query("$SELECT order by b.code", emptyMap<String, Any>()) { rs, _ -> rs.board() }

    override fun find(code: String): Board? =
        jdbc.query("$SELECT where b.code = :code", mapOf("code" to code)) { rs, _ -> rs.board() }.firstOrNull()

    /** 같은 코드가 동시에 들어와도 유니크 키가 한 쪽만 통과시킨다 — 졌으면 false */
    override fun create(code: String, name: String, description: String?, now: Instant): Boolean =
        try {
            jdbc.update(
                "insert into skeleton_boards (code, name, description, created_at) values (:code, :name, :description, :now)",
                MapSqlParameterSource().addValue("code", code).addValue("name", name).addValue("description", description)
                    .addValue("now", dialect.instantParam(now)),
            )
            true
        } catch (e: DuplicateKeyException) {
            false
        }

    private fun ResultSet.board() =
        Board(getString("code"), getString("name"), getString("description"), long(this, "post_count"), instant(dialect, "created_at"))

    private companion object {
        const val SELECT = """
            select b.code, b.name, b.description, b.created_at, coalesce(c.cnt, 0) as post_count
            from skeleton_boards b
            left join (select board_code, count(*) as cnt from skeleton_board_posts where status = 'PUBLISHED' group by board_code) c
                on c.board_code = b.code
        """
    }
}

class JdbcPostRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
) : PostRepository {
    override fun insert(post: NewPost): Post {
        val id = tx.execute {
            val keys = GeneratedKeyHolder()
            jdbc.update(
                """
                insert into skeleton_board_posts
                    (board_code, author_id, title, body, status, pinned, view_count, comment_count, reaction_count, attachment_count, created_at, updated_at)
                values (:board, :author, :title, :body, :status, :pinned, 0, 0, 0, :attachmentCount, :now, :now)
                """.trimIndent(),
                MapSqlParameterSource().addValue("board", post.boardCode).addValue("author", post.authorId).addValue("title", post.title)
                    .addValue("body", post.body).addValue("status", post.status.name).addValue("pinned", false)
                    .addValue("attachmentCount", post.attachments.size).addValue("now", dialect.instantParam(post.now)),
                keys,
                arrayOf("id"),
            )
            val newId = keys.key!!.toLong()   // MySQL 은 키 칼럼 이름이 "GENERATED_KEY" 라 이름이 아니라 첫 키로 읽는다
            replaceAttachments(newId, post.attachments)
            newId
        }!!
        return find(id)!!
    }

    override fun find(id: Long): Post? {
        val post = jdbc.query(
            "select * from skeleton_board_posts where id = :id", mapOf("id" to id),
        ) { rs, _ -> rs.post(emptyList()) }.firstOrNull() ?: return null
        val keys = jdbc.queryForList(
            "select storage_key from skeleton_board_post_attachments where post_id = :id order by sort_order", mapOf("id" to id), String::class.java,
        )
        return post.copy(attachments = keys.filterNotNull())
    }

    override fun update(id: Long, change: PostChange, now: Instant): Post? {
        val sets = mutableListOf("updated_at = :now")
        val params = MapSqlParameterSource().addValue("id", id).addValue("now", dialect.instantParam(now))
        change.title?.let { sets += "title = :title"; params.addValue("title", it) }
        change.body?.let { sets += "body = :body"; params.addValue("body", it) }
        change.status?.let { sets += "status = :status"; params.addValue("status", it.name) }
        change.pinned?.let { sets += "pinned = :pinned"; params.addValue("pinned", it) }
        change.attachments?.let { sets += "attachment_count = :attachmentCount"; params.addValue("attachmentCount", it.size) }
        val updated = tx.execute {
            val rows = jdbc.update("update skeleton_board_posts set ${sets.joinToString()} where id = :id", params)
            if (rows > 0 && change.attachments != null) replaceAttachments(id, change.attachments!!)
            rows
        }!!
        return if (updated == 0) null else find(id)
    }

    override fun incrementViews(id: Long) {
        jdbc.update("update skeleton_board_posts set view_count = view_count + 1 where id = :id", mapOf("id" to id))
    }

    override fun page(query: PostQuery): PageResult<PostListItem> {
        val params = MapSqlParameterSource().addValue("board", query.boardCode).addValue("statuses", query.statuses.map { it.name })
        val where = buildString {
            append("p.board_code = :board and p.status in (:statuses)")
            query.authorId?.let { append(" and p.author_id = :author"); params.addValue("author", it) }
            query.search?.let {
                append(" and (lower(p.title) like :q escape '!' or lower(p.body) like :q escape '!')")
                params.addValue("q", "%" + escapeLike(it.lowercase()) + "%")
            }
        }
        val order = when {
            query.reactionType != null -> {
                params.addValue("reactionType", query.reactionType)
                "(select count(*) from skeleton_board_reactions r where r.target_type = 'POST' and r.target_id = p.id and r.reaction_type = :reactionType) desc, "
            }
            query.sort == PostSort.REACTIONS -> "p.reaction_count desc, "
            query.sort == PostSort.COMMENTS -> "p.comment_count desc, "
            else -> ""
        }
        val total = jdbc.queryForObject("select count(*) from skeleton_board_posts p where $where", params, Long::class.java) ?: 0L
        params.addValue("excerpt", query.excerptLength).addValue("limit", query.size).addValue("offset", query.page * query.size)
        val rows = jdbc.query(
            """
            select p.id, p.board_code, p.author_id, p.title, left(p.body, :excerpt) as excerpt, p.status, p.pinned, p.view_count,
                   p.comment_count, p.reaction_count, p.attachment_count, p.created_at, p.updated_at
            from skeleton_board_posts p
            where $where
            order by p.pinned desc, $order p.created_at desc, p.id desc
            limit :limit offset :offset
            """.trimIndent(),
            params,
        ) { rs, _ ->
            PostListItem(
                id = rs.getLong("id"), boardCode = rs.getString("board_code"), authorId = rs.getString("author_id"), title = rs.getString("title"),
                excerpt = rs.getString("excerpt"), status = PostStatus.valueOf(rs.getString("status")), pinned = rs.getBoolean("pinned"),
                viewCount = rs.getLong("view_count"), commentCount = rs.getLong("comment_count"), reactionCount = rs.getLong("reaction_count"),
                attachmentCount = rs.getInt("attachment_count"), createdAt = rs.instant(dialect, "created_at"), updatedAt = rs.instant(dialect, "updated_at"),
            )
        }
        return PageResult(rows, total)
    }

    private fun replaceAttachments(postId: Long, keys: List<String>) {
        jdbc.update("delete from skeleton_board_post_attachments where post_id = :id", mapOf("id" to postId))
        if (keys.isNotEmpty()) {
            jdbc.batchUpdate(
                "insert into skeleton_board_post_attachments (post_id, sort_order, storage_key) values (:postId, :order, :key)",
                keys.mapIndexed { i, key -> MapSqlParameterSource().addValue("postId", postId).addValue("order", i).addValue("key", key) }.toTypedArray(),
            )
        }
    }

    private fun ResultSet.post(attachments: List<String>) =
        Post(
            id = getLong("id"), boardCode = getString("board_code"), authorId = getString("author_id"), title = getString("title"), body = getString("body"),
            status = PostStatus.valueOf(getString("status")), pinned = getBoolean("pinned"), viewCount = getLong("view_count"),
            commentCount = getLong("comment_count"), reactionCount = getLong("reaction_count"), attachments = attachments,
            createdAt = instant(dialect, "created_at"), updatedAt = instant(dialect, "updated_at"),
        )
}

/** LIKE 의 와일드카드(`%` `_`)와 이스케이프 문자(`!`)를 글자 그대로 찾게 한다 — 두 DB 에 똑같이 통하는 `escape '!'` */
internal fun escapeLike(term: String): String = term.replace("!", "!!").replace("%", "!%").replace("_", "!_")

class JdbcCommentRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
) : CommentRepository {
    override fun insert(comment: NewComment): Comment? {
        val id = tx.execute {
            // 글 행을 먼저 잠그며 카운터를 올린다 (잠금 순서 규칙). 글이 없으면 0 행.
            val bumped = jdbc.update("update skeleton_board_posts set comment_count = comment_count + 1 where id = :post", mapOf("post" to comment.postId))
            if (bumped == 0) return@execute null
            val keys = GeneratedKeyHolder()
            jdbc.update(
                """
                insert into skeleton_board_comments (post_id, parent_id, root_id, depth, author_id, body, status, reaction_count, created_at, updated_at)
                values (:post, :parent, :root, :depth, :author, :body, 'PUBLISHED', 0, :now, :now)
                """.trimIndent(),
                MapSqlParameterSource().addValue("post", comment.postId).addValue("parent", comment.parentId, java.sql.Types.BIGINT)
                    .addValue("root", comment.rootId, java.sql.Types.BIGINT).addValue("depth", comment.depth).addValue("author", comment.authorId)
                    .addValue("body", comment.body).addValue("now", dialect.instantParam(comment.now)),
                keys,
                arrayOf("id"),
            )
            keys.key!!.toLong()
        } ?: return null
        return find(id)
    }

    override fun find(id: Long): Comment? =
        jdbc.query("select * from skeleton_board_comments where id = :id", mapOf("id" to id)) { rs, _ -> rs.comment() }.firstOrNull()

    override fun updateBody(id: Long, body: String, now: Instant): Comment? {
        val rows = jdbc.update(
            "update skeleton_board_comments set body = :body, updated_at = :now where id = :id",
            MapSqlParameterSource().addValue("id", id).addValue("body", body).addValue("now", dialect.instantParam(now)),
        )
        return if (rows == 0) null else find(id)
    }

    override fun setStatus(id: Long, status: CommentStatus, now: Instant): Comment? {
        tx.execute {
            val postId = jdbc.queryForList("select post_id from skeleton_board_comments where id = :id", mapOf("id" to id), Long::class.java).firstOrNull()
                ?: return@execute null
            // 잠금 순서: 글 → 댓글
            jdbc.queryForList("select id from skeleton_board_posts where id = :post for update", mapOf("post" to postId), Long::class.java)
            val old = jdbc.queryForObject("select status from skeleton_board_comments where id = :id for update", mapOf("id" to id), String::class.java)!!
            jdbc.update(
                "update skeleton_board_comments set status = :status, updated_at = :now where id = :id",
                MapSqlParameterSource().addValue("id", id).addValue("status", status.name).addValue("now", dialect.instantParam(now)),
            )
            val delta = (if (status == CommentStatus.PUBLISHED) 1 else 0) - (if (old == CommentStatus.PUBLISHED.name) 1 else 0)
            if (delta != 0) jdbc.update("update skeleton_board_posts set comment_count = comment_count + :delta where id = :post", mapOf("delta" to delta, "post" to postId))
            Unit
        } ?: return null
        return find(id)
    }

    override fun rootPage(query: CommentRootQuery): PageResult<Comment> {
        val params = mapOf("post" to query.postId, "limit" to query.size, "offset" to query.page * query.size)
        val total = jdbc.queryForObject(
            "select count(*) from skeleton_board_comments where post_id = :post and parent_id is null", params, Long::class.java,
        ) ?: 0L
        val order = when (query.sort) {
            CommentSort.OLDEST -> "created_at, id"
            CommentSort.LATEST -> "created_at desc, id desc"
            CommentSort.REACTIONS -> "reaction_count desc, created_at, id"
        }
        val rows = jdbc.query(
            "select * from skeleton_board_comments where post_id = :post and parent_id is null order by $order limit :limit offset :offset",
            params,
        ) { rs, _ -> rs.comment() }
        return PageResult(rows, total)
    }

    /** 쿼리 한 번 — 최상위 댓글들의 모든 자손 (root_id IN …) */
    override fun descendants(rootIds: Collection<Long>): List<Comment> =
        if (rootIds.isEmpty()) emptyList()
        else jdbc.query(
            "select * from skeleton_board_comments where root_id in (:roots) order by created_at, id", mapOf("roots" to rootIds),
        ) { rs, _ -> rs.comment() }

    private fun ResultSet.comment(): Comment {
        val id = getLong("id")
        val parent = getLong("parent_id").takeUnless { wasNull() }
        val root = getLong("root_id").takeUnless { wasNull() }
        return Comment(
            id = id, postId = getLong("post_id"), parentId = parent, rootId = root ?: id, depth = getInt("depth"), authorId = getString("author_id"),
            body = getString("body"), status = CommentStatus.valueOf(getString("status")), reactionCount = getLong("reaction_count"),
            createdAt = instant(dialect, "created_at"), updatedAt = instant(dialect, "updated_at"),
        )
    }
}

class JdbcReactionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
) : ReactionRepository {
    /**
     * 대상 행을 잠근 채로 — 같은 대상에 대한 모든 반응 변경이 줄을 서므로 "SINGLE: 계정당 하나" 가 동시 요청에서도 깨지지 않는다
     * (잠그지 않으면 같은 계정의 LIKE · DISLIKE 가 서로의 삭제를 못 보고 둘 다 삽입된다). 유니크 키는 마지막 안전망이다.
     */
    override fun react(target: ReactionTarget, targetId: Long, accountId: String, type: String, mode: ReactionMode, now: Instant): Boolean =
        tx.execute {
            if (!lock(target, targetId)) return@execute false
            val key = MapSqlParameterSource().addValue("t", target.name).addValue("id", targetId).addValue("who", accountId).addValue("type", type)
            val removed = if (mode == ReactionMode.SINGLE) {
                jdbc.update("delete from skeleton_board_reactions where target_type = :t and target_id = :id and account_id = :who and reaction_type <> :type", key)
            } else 0
            val present = (jdbc.queryForObject(
                "select count(*) from skeleton_board_reactions where target_type = :t and target_id = :id and account_id = :who and reaction_type = :type",
                key, Long::class.java,
            ) ?: 0L) > 0
            val added = if (present) 0 else jdbc.update(
                "insert into skeleton_board_reactions (target_type, target_id, account_id, reaction_type, created_at) values (:t, :id, :who, :type, :now)",
                key.addValue("now", dialect.instantParam(now)),
            )
            bump(target, targetId, added - removed)
            true
        } ?: false

    override fun remove(target: ReactionTarget, targetId: Long, accountId: String, type: String?): Boolean =
        tx.execute {
            if (!lock(target, targetId)) return@execute false
            val params = MapSqlParameterSource().addValue("t", target.name).addValue("id", targetId).addValue("who", accountId)
            val removed = jdbc.update(
                "delete from skeleton_board_reactions where target_type = :t and target_id = :id and account_id = :who" +
                    (if (type != null) " and reaction_type = :type" else ""),
                if (type != null) params.addValue("type", type) else params,
            )
            bump(target, targetId, -removed)
            true
        } ?: false

    override fun counts(target: ReactionTarget, targetIds: Collection<Long>): Map<Long, Map<String, Long>> =
        if (targetIds.isEmpty()) emptyMap()
        else {
            val result = linkedMapOf<Long, MutableMap<String, Long>>()
            jdbc.query(
                """
                select target_id, reaction_type, count(*) as cnt from skeleton_board_reactions
                where target_type = :t and target_id in (:ids) group by target_id, reaction_type
                """.trimIndent(),
                mapOf("t" to target.name, "ids" to targetIds),
            ) { rs ->
                result.getOrPut(rs.getLong("target_id")) { linkedMapOf() }[rs.getString("reaction_type")] = rs.getLong("cnt")
            }
            result
        }

    override fun mine(target: ReactionTarget, targetIds: Collection<Long>, accountId: String): Map<Long, Set<String>> =
        if (targetIds.isEmpty()) emptyMap()
        else {
            val result = linkedMapOf<Long, MutableSet<String>>()
            jdbc.query(
                "select target_id, reaction_type from skeleton_board_reactions where target_type = :t and target_id in (:ids) and account_id = :who",
                mapOf("t" to target.name, "ids" to targetIds, "who" to accountId),
            ) { rs -> result.getOrPut(rs.getLong("target_id")) { linkedSetOf() } += rs.getString("reaction_type") }
            result
        }

    private fun table(target: ReactionTarget) = if (target == ReactionTarget.POST) "skeleton_board_posts" else "skeleton_board_comments"

    private fun lock(target: ReactionTarget, id: Long): Boolean =
        jdbc.queryForList("select id from ${table(target)} where id = :id for update", mapOf("id" to id), Long::class.java).isNotEmpty()

    private fun bump(target: ReactionTarget, id: Long, delta: Int) {
        if (delta != 0) jdbc.update("update ${table(target)} set reaction_count = reaction_count + :delta where id = :id", mapOf("delta" to delta, "id" to id))
    }
}
