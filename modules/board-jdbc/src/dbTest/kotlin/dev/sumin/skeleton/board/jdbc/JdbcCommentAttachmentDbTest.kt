package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.Revision
import dev.sumin.skeleton.board.RevisionTarget
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import kotlin.test.Test
import kotlin.test.assertEquals

/** 댓글 첨부: 왕복, 쪽의 모든 댓글을 쿼리 한 번으로, 고치기 · 판 승인으로 교체 */
class JdbcCommentAttachmentDbTest {
    private val db = BoardDb
    private val revisions = JdbcBoardRevisionRepository(db.jdbc, db.tx, DbTestDatabase.dialect)

    @Test
    fun `attachments round-trip in order and a page of comments needs one query`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val a = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "a", T0, attachments = listOf("k/1", "k/2", "k/3")))!!
        val b = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "b", T0))!!
        val c = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "c", T0, attachments = listOf("k/x")))!!
        var result = emptyMap<Long, List<String>>()
        val statements = db.dataSource.count { result = db.comments.attachments(listOf(a.id, b.id, c.id)) }
        assertEquals(1, statements)
        assertEquals(listOf("k/1", "k/2", "k/3"), result[a.id])
        assertEquals(null, result[b.id])
        assertEquals(listOf("k/x"), result[c.id])
        assertEquals(3L, db.scalar("select attachment_count from board_comments where id = :id", "id" to a.id))
    }

    @Test
    fun `updateContent replaces the pictures, null keeps them, an empty list clears them`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val a = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "a", T0, attachments = listOf("k/1", "k/2")))!!
        db.comments.updateContent(a.id, "a2", listOf("k/9"), T0.plusSeconds(1))
        assertEquals(listOf("k/9"), db.comments.attachments(listOf(a.id))[a.id])
        db.comments.updateContent(a.id, "a3", null, T0.plusSeconds(2))
        assertEquals(listOf("k/9"), db.comments.attachments(listOf(a.id))[a.id])
        assertEquals("a3", db.comments.find(a.id)!!.body)
        db.comments.updateContent(a.id, "a4", emptyList(), T0.plusSeconds(3))
        assertEquals(emptyMap(), db.comments.attachments(listOf(a.id)))
        assertEquals(0L, db.scalar("select attachment_count from board_comments where id = :id", "id" to a.id))
    }

    @Test
    fun `approving a comment revision swaps the pictures`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val a = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "a", T0, attachments = listOf("old")))!!
        revisions.submit(Revision(RevisionTarget.COMMENT, a.id, board, post.id, null, "a-new", listOf("new1", "new2"), T0, T0))
        revisions.apply(RevisionTarget.COMMENT, a.id, T0.plusSeconds(5), revisions.find(RevisionTarget.COMMENT, listOf(a.id)).getValue(a.id).updatedAt)
        assertEquals(listOf("new1", "new2"), db.comments.attachments(listOf(a.id))[a.id])
        assertEquals("a-new", db.comments.find(a.id)!!.body)
        assertEquals(2L, db.scalar("select attachment_count from board_comments where id = :id", "id" to a.id))
    }

    @Test
    fun `usage says a key is in use when a comment or a waiting revision holds it, not otherwise`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val a = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "a", T0, attachments = listOf("u/on-comment")))!!
        revisions.submit(Revision(RevisionTarget.COMMENT, a.id, board, post.id, null, "a2", listOf("u/on-revision"), T0, T0))
        val usage = JdbcUploadedKeyUsage(db.jdbc)
        assertEquals(setOf("u/on-comment", "u/on-revision"), usage.inUse(listOf("u/on-comment", "u/on-revision", "u/nowhere")))
        assertEquals(emptySet(), usage.inUse(emptyList()))
    }
}
