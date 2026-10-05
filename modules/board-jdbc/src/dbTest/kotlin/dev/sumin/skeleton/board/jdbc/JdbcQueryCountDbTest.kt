package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.BoardAccess
import dev.sumin.skeleton.board.BoardCaller
import dev.sumin.skeleton.board.BoardContentRules
import dev.sumin.skeleton.board.BoardProperties
import dev.sumin.skeleton.board.CommentService
import dev.sumin.skeleton.board.CommentSort
import dev.sumin.skeleton.board.DefaultBoardPolicy
import dev.sumin.skeleton.board.NoopBoardNotifier
import dev.sumin.skeleton.board.NoopBoardRateLimiter
import dev.sumin.skeleton.board.PostListRequest
import dev.sumin.skeleton.board.PostService
import dev.sumin.skeleton.board.ReactionMode
import dev.sumin.skeleton.board.ReactionSupport
import dev.sumin.skeleton.board.ReactionTarget
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import dev.sumin.skeleton.common.time.TimeProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 목록이 N+1 이 아니다 — 행이 3 개든 30 개든 SQL 문장 수가 같다. */
class JdbcQueryCountDbTest {
    private val db = BoardDb
    private val props = BoardProperties()
    private val policy = DefaultBoardPolicy()
    private val access = BoardAccess(db.boards, db.posts, db.comments, policy)
    private val support = ReactionSupport(db.reactions, props)
    private val time = TimeProvider.fixed(T0)
    private val comments = CommentService(access, db.comments, support, policy, BoardContentRules(props), props, NoopBoardRateLimiter, NoopBoardNotifier, time)
    private val posts = PostService(access, db.posts, support, policy, BoardContentRules(props), props, NoopBoardRateLimiter, time)
    private val me = BoardCaller("viewer")

    private fun threadListingStatements(roots: Int): Int {
        val board = db.newBoard()
        val post = db.newPost(board).id
        repeat(roots) { i ->
            val root = db.comment(db.comments, post, author = "a$i", at = T0.plusSeconds(i.toLong()))
            val reply = db.comment(db.comments, post, root.id, root.id, 1, "b$i", T0.plusSeconds(i.toLong()))
            db.comment(db.comments, post, reply.id, root.id, 2, "c$i", T0.plusSeconds(i.toLong()))
            db.reactions.react(ReactionTarget.COMMENT, root.id, "viewer", "LIKE", ReactionMode.SINGLE, T0)
        }
        var page: dev.sumin.skeleton.board.PageResult<dev.sumin.skeleton.board.CommentView>? = null
        val statements = db.dataSource.count { page = comments.list(me, board, post, 0, 50, CommentSort.OLDEST) }
        assertEquals(roots, page!!.values.size)
        assertEquals(2, page!!.values.first().replies.size)
        assertTrue(page!!.values.all { it.replies.size == 2 })
        return statements
    }

    @Test
    fun `the comment thread listing sends the same number of statements for 3 roots and for 30`() {
        val few = threadListingStatements(3)
        val many = threadListingStatements(30)
        assertEquals(few, many, "statements: 3 roots=$few, 30 roots=$many")
        assertTrue(many <= 9, "thread listing should stay a handful of statements, was $many")
    }

    private fun postListingStatements(count: Int): Int {
        val board = db.newBoard()
        repeat(count) { i ->
            val p = db.newPost(board, at = T0.plusSeconds(i.toLong()))
            db.reactions.react(ReactionTarget.POST, p.id, "viewer", "LIKE", ReactionMode.SINGLE, T0)
        }
        var size = 0
        val statements = db.dataSource.count { size = posts.list(me, board, PostListRequest(0, 50)).values.size }
        assertEquals(count, size)
        return statements
    }

    @Test
    fun `the post listing sends the same number of statements for 3 posts and for 30`() {
        val few = postListingStatements(3)
        val many = postListingStatements(30)
        assertEquals(few, many, "statements: 3 posts=$few, 30 posts=$many")
        assertTrue(many <= 5, "post listing should stay a handful of statements, was $many")
    }

    @Test
    fun `descendants counts and mine are one statement each, however many ids`() {
        val board = db.newBoard()
        val post = db.newPost(board).id
        val roots = (1..25).map { db.comment(db.comments, post) }
        assertEquals(1, db.dataSource.count { db.comments.descendants(roots.map { it.id }) })
        assertEquals(1, db.dataSource.count { db.reactions.counts(ReactionTarget.COMMENT, roots.map { it.id }) })
        assertEquals(1, db.dataSource.count { db.reactions.mine(ReactionTarget.COMMENT, roots.map { it.id }, "viewer") })
        assertEquals(2, db.dataSource.count { db.comments.rootPage(dev.sumin.skeleton.board.CommentRootQuery(post, 0, 10, CommentSort.OLDEST)) })
    }
}
