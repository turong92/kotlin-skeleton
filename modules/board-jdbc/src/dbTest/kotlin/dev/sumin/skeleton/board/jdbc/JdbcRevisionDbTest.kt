package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.PostChange
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.Revision
import dev.sumin.skeleton.board.RevisionTarget
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import dev.sumin.skeleton.board.RevisionSubmit
import dev.sumin.skeleton.board.RevisionWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 고친 판의 SQL: 덮어쓰기, 승인(원래 행으로 옮기고 지움), 공개를 떠나면 같은 트랜잭션에서 버림, 덮어쓰기와 승인이 동시에 와도 깨지지 않음 */
class JdbcRevisionDbTest {
    private val db = BoardDb
    private val revisions = JdbcBoardRevisionRepository(db.jdbc, db.tx, DbTestDatabase.dialect)

    private fun ver(target: RevisionTarget, id: Long) = revisions.find(target, listOf(id)).getValue(id).updatedAt

    private fun postRevision(board: String, id: Long, body: String, keys: List<String> = emptyList(), at: Long = 1) =
        Revision(RevisionTarget.POST, id, board, id, "제목 $body", body, keys, T0.plusSeconds(at), T0.plusSeconds(at))

    @Test
    fun `submit overwrites in place - one row per target, attachments replaced`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        assertEquals(RevisionSubmit.CREATED, revisions.submit(postRevision(board, post.id, "첫 판", listOf("a", "b"))))
        assertEquals(RevisionSubmit.REPLACED, revisions.submit(postRevision(board, post.id, "둘째 판", listOf("c"), at = 2)))
        assertEquals(1L, db.scalar("select count(*) from board_revisions where target_id = :id and target_type = 'POST'", "id" to post.id))
        val rev = revisions.find(RevisionTarget.POST, listOf(post.id)).getValue(post.id)
        assertEquals("둘째 판", rev.body)
        assertEquals(listOf("c"), rev.attachments)
        assertEquals("body", db.posts.find(post.id)!!.body, "the public row is untouched until approval")
    }

    @Test
    fun `apply moves title, body and attachments onto the post and deletes the revision`() {
        val board = db.newBoard()
        val post = db.newPost(board, attachments = listOf("old"))
        revisions.submit(postRevision(board, post.id, "새 본문", listOf("n1", "n2")))
        assertEquals(RevisionWrite.DONE, revisions.apply(RevisionTarget.POST, post.id, T0.plusSeconds(10), ver(RevisionTarget.POST, post.id)))
        val after = db.posts.find(post.id)!!
        assertEquals("제목 새 본문", after.title)
        assertEquals("새 본문", after.body)
        assertEquals(listOf("n1", "n2"), after.attachments)
        assertEquals(T0.plusSeconds(10), after.updatedAt)
        assertTrue(revisions.find(RevisionTarget.POST, listOf(post.id)).isEmpty())
        assertEquals(0L, db.scalar("select count(*) from board_revision_attachments where target_id = :id and target_type = 'POST'", "id" to post.id))
        assertEquals(RevisionWrite.NONE, revisions.apply(RevisionTarget.POST, post.id, T0.plusSeconds(11), T0), "nothing left to apply")
    }

    @Test
    fun `leaving PUBLISHED drops the revision in the same transaction, so a later apply finds nothing`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        revisions.submit(postRevision(board, post.id, "판"))
        db.posts.update(post.id, PostChange(status = PostStatus.HIDDEN), T0.plusSeconds(1))
        assertTrue(revisions.find(RevisionTarget.POST, listOf(post.id)).isEmpty())
        assertEquals(RevisionWrite.NONE, revisions.apply(RevisionTarget.POST, post.id, T0.plusSeconds(2), T0))

        val c = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "원본", T0))!!
        revisions.submit(Revision(RevisionTarget.COMMENT, c.id, board, post.id, null, "고침", emptyList(), T0, T0))
        db.comments.setStatus(c.id, CommentStatus.HIDDEN, T0.plusSeconds(3))
        assertTrue(revisions.find(RevisionTarget.COMMENT, listOf(c.id)).isEmpty())
        assertEquals("원본", db.comments.find(c.id)!!.body)
    }

    @Test
    fun `comment revisions apply the text and the pending lists page oldest first`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val c1 = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "1", T0))!!
        val c2 = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "2", T0))!!
        revisions.submit(Revision(RevisionTarget.COMMENT, c2.id, board, post.id, null, "2b", emptyList(), T0, T0.plusSeconds(2)))
        revisions.submit(Revision(RevisionTarget.COMMENT, c1.id, board, post.id, null, "1b", emptyList(), T0, T0.plusSeconds(1)))
        assertEquals(listOf(c1.id, c2.id), revisions.pending(RevisionTarget.COMMENT, null, post.id, 0, 10).values.map { it.targetId })
        assertEquals(2L, revisions.pending(RevisionTarget.COMMENT, null, post.id, 0, 10).totalElements)
        assertEquals(RevisionWrite.DONE, revisions.apply(RevisionTarget.COMMENT, c1.id, T0.plusSeconds(5), ver(RevisionTarget.COMMENT, c1.id)))
        assertEquals("1b", db.comments.find(c1.id)!!.body)
        assertNull(revisions.find(RevisionTarget.COMMENT, listOf(c1.id))[c1.id])
    }

    @Test
    fun `approving or rejecting a version the owner already overwrote does nothing (CHANGED), and a hidden target takes no new revision`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        revisions.submit(postRevision(board, post.id, "v1"))
        val seen = ver(RevisionTarget.POST, post.id)
        assertEquals(RevisionSubmit.REPLACED, revisions.submit(postRevision(board, post.id, "v2", at = 1)))
        assertTrue(ver(RevisionTarget.POST, post.id).isAfter(seen), "every overwrite raises the version, even within the same microsecond")
        assertEquals(RevisionWrite.CHANGED, revisions.apply(RevisionTarget.POST, post.id, T0.plusSeconds(5), seen))
        assertEquals("body", db.posts.find(post.id)!!.body)
        assertEquals(RevisionWrite.CHANGED, revisions.reject(RevisionTarget.POST, post.id, seen))
        assertEquals("v2", revisions.find(RevisionTarget.POST, listOf(post.id)).getValue(post.id).body)
        assertEquals(RevisionWrite.DONE, revisions.reject(RevisionTarget.POST, post.id, ver(RevisionTarget.POST, post.id)))

        db.posts.update(post.id, PostChange(status = PostStatus.HIDDEN), T0.plusSeconds(6))
        assertEquals(RevisionSubmit.TARGET_GONE, revisions.submit(postRevision(board, post.id, "late")))
        assertTrue(revisions.find(RevisionTarget.POST, listOf(post.id)).isEmpty(), "nothing is left on the hidden post")
    }

    @Test
    fun `a direct edit drops the waiting revision in the same transaction`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        revisions.submit(postRevision(board, post.id, "v1"))
        db.posts.update(post.id, PostChange(title = "operator"), T0.plusSeconds(1))
        assertTrue(revisions.find(RevisionTarget.POST, listOf(post.id)).isEmpty())
        val c = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "c", T0))!!
        revisions.submit(Revision(RevisionTarget.COMMENT, c.id, board, post.id, null, "c2", emptyList(), T0, T0))
        db.comments.updateContent(c.id, "direct", null, T0.plusSeconds(2))
        assertTrue(revisions.find(RevisionTarget.COMMENT, listOf(c.id)).isEmpty())
    }

    @Test
    fun `overwriting and approving at the same moment never leaves a half state`() {
        val board = db.newBoard()
        repeat(15) { round ->
            val post = db.newPost(board, attachments = listOf("old"))
            revisions.submit(postRevision(board, post.id, "판1", listOf("k1", "k2")))
            val first = ver(RevisionTarget.POST, post.id)
            parallel(2) { i ->
                if (i == 0) revisions.apply(RevisionTarget.POST, post.id, T0.plusSeconds(20), first)
                else revisions.submit(postRevision(board, post.id, "판2", listOf("m1"), at = 30))
            }
            val after = db.posts.find(post.id)!!
            val rev = revisions.find(RevisionTarget.POST, listOf(post.id))[post.id]
            // 승인이 먼저면 판1 이 옮겨지고 판2 가 새로 남는다. 덮어쓰기가 먼저면 승인은 CHANGED 라 공개본은 그대로고 판2 만 남는다. 판2 가 몰래 공개되는 일은 없다.
            when (after.body) {
                "판1" -> { assertEquals(listOf("k1", "k2"), after.attachments, "round $round"); assertEquals("판2", rev?.body, "round $round") }
                "body" -> { assertEquals(listOf("old"), after.attachments, "round $round"); assertEquals("판2", rev?.body, "round $round") }
                else -> error("round $round: unexpected body ${after.body}")
            }
            assertEquals(if (rev == null) 0L else 1L, db.scalar("select count(*) from board_revisions where target_id = :id and target_type = 'POST'", "id" to post.id))
            assertEquals(rev?.attachments?.size?.toLong() ?: 0L, db.scalar("select count(*) from board_revision_attachments where target_id = :id and target_type = 'POST'", "id" to post.id))
        }
    }
}
