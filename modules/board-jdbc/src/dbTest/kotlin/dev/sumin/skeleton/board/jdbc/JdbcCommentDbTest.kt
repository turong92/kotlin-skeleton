package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.CommentRootQuery
import dev.sumin.skeleton.board.CommentSort
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.ReactionMode
import dev.sumin.skeleton.board.ReactionTarget
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcCommentDbTest {
    private val db = BoardDb
    private fun post() = db.newPost(db.newBoard()).id

    @Test
    fun `a top-level comment is its own root and the post counts it`() {
        val post = post()
        val c = db.comment(db.comments, post)
        assertEquals(c.id, c.rootId)
        assertNull(c.parentId)
        assertEquals(listOf(0, CommentStatus.PUBLISHED, 0L, T0), listOf(c.depth, c.status, c.reactionCount, c.createdAt))
        assertEquals(1L, db.posts.find(post)!!.commentCount)
        assertEquals(c, db.comments.find(c.id))
    }

    @Test
    fun `replies carry parent root and depth`() {
        val post = post()
        val root = db.comment(db.comments, post)
        val reply = db.comment(db.comments, post, parent = root.id, root = root.id, depth = 1)
        val deep = db.comment(db.comments, post, parent = reply.id, root = root.id, depth = 2)
        assertEquals(listOf(root.id, reply.id), listOf(reply.parentId, deep.parentId))
        assertEquals(listOf(root.id, root.id), listOf(reply.rootId, deep.rootId))
        assertEquals(3L, db.posts.find(post)!!.commentCount)
    }

    @Test
    fun `a comment on a missing post is refused`() {
        assertNull(db.comments.insert(NewComment(-5, null, null, 0, "a", "c", T0)))
    }

    @Test
    fun `body edit and soft delete keep the row, and the post count follows PUBLISHED`() {
        val post = post()
        val c = db.comment(db.comments, post)
        val later = T0.plusSeconds(60)
        assertEquals("edited", db.comments.updateBody(c.id, "edited", later)!!.body)
        assertEquals(later, db.comments.find(c.id)!!.updatedAt)

        assertEquals(CommentStatus.DELETED, db.comments.setStatus(c.id, CommentStatus.DELETED, later)!!.status)
        assertEquals(0L, db.posts.find(post)!!.commentCount)
        db.comments.setStatus(c.id, CommentStatus.HIDDEN, later)
        assertEquals(0L, db.posts.find(post)!!.commentCount)
        db.comments.setStatus(c.id, CommentStatus.PUBLISHED, later)
        assertEquals(1L, db.posts.find(post)!!.commentCount)
        db.comments.setStatus(c.id, CommentStatus.PUBLISHED, later)
        assertEquals(1L, db.posts.find(post)!!.commentCount)
        assertEquals("edited", db.comments.find(c.id)!!.body)
        assertNull(db.comments.setStatus(-1, CommentStatus.HIDDEN, later))
    }

    @Test
    fun `top-level comments are paged with a total, in the requested order`() {
        val post = post()
        val a = db.comment(db.comments, post, at = T0)
        val b = db.comment(db.comments, post, at = T0.plusSeconds(1))
        val c = db.comment(db.comments, post, at = T0.plusSeconds(2))
        db.comment(db.comments, post, parent = a.id, root = a.id, depth = 1, at = T0.plusSeconds(3))
        db.reactions.react(ReactionTarget.COMMENT, b.id, "u1", "LIKE", ReactionMode.SINGLE, T0)

        val oldest = db.comments.rootPage(CommentRootQuery(post, 0, 2, CommentSort.OLDEST))
        assertEquals(listOf(a.id, b.id), oldest.values.map { it.id })
        assertEquals(3, oldest.totalElements)
        assertEquals(listOf(c.id), db.comments.rootPage(CommentRootQuery(post, 1, 2, CommentSort.OLDEST)).values.map { it.id })
        assertEquals(listOf(c.id, b.id, a.id), db.comments.rootPage(CommentRootQuery(post, 0, 10, CommentSort.LATEST)).values.map { it.id })
        assertEquals(b.id, db.comments.rootPage(CommentRootQuery(post, 0, 10, CommentSort.REACTIONS)).values.first().id)
    }

    @Test
    fun `all descendants of several roots come back in creation order, deleted ones included`() {
        val post = post()
        val r1 = db.comment(db.comments, post, at = T0)
        val r2 = db.comment(db.comments, post, at = T0.plusSeconds(1))
        val a = db.comment(db.comments, post, r1.id, r1.id, 1, at = T0.plusSeconds(2))
        val b = db.comment(db.comments, post, r2.id, r2.id, 1, at = T0.plusSeconds(3))
        val aa = db.comment(db.comments, post, a.id, r1.id, 2, at = T0.plusSeconds(4))
        db.comments.setStatus(a.id, CommentStatus.DELETED, T0)

        val rows = db.comments.descendants(listOf(r1.id, r2.id))
        assertEquals(listOf(a.id, b.id, aa.id), rows.map { it.id })
        assertEquals(CommentStatus.DELETED, rows.first().status)
        assertEquals(listOf(a.id), db.comments.descendants(listOf(r1.id)).map { it.id }.take(1))
        assertTrue(db.comments.descendants(emptyList()).isEmpty())
    }
}
