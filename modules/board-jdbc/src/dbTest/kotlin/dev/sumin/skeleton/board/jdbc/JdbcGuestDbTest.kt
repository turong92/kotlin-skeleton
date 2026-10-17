package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.BoardSettings
import dev.sumin.skeleton.board.CommentRootQuery
import dev.sumin.skeleton.board.CommentSort
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.GuestWrite
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.NewPost
import dev.sumin.skeleton.board.PostQuery
import dev.sumin.skeleton.board.PostSort
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 비회원 열: 게시판 설정, 이름 · 해시 왕복(해시는 모델에 실리지 않는다), 승인 대기 댓글은 카운터에 넣지 않는다, 넘겨받기는 비회원 행을 고르지 않는다 */
class JdbcGuestDbTest {
    private val db = BoardDb

    @Test
    fun `a board stores its guest write setting and defaults to NONE`() {
        val plain = db.newBoard()
        assertEquals(BoardSettings(), db.boards.find(plain)!!.settings)
        val code = "g-${System.nanoTime() % 1_000_000}"
        assertTrue(db.boards.create(code, "Guests", null, T0, BoardSettings(guestWrite = GuestWrite.ALL)))
        assertEquals(GuestWrite.ALL, db.boards.find(code)!!.settings.guestWrite)
        assertEquals(GuestWrite.ALL, db.boards.findAll().first { it.code == code }.settings.guestWrite)
    }

    @Test
    fun `guest name and secret hash round-trip, the hash only through guestSecretHash`() {
        val board = db.newBoard()
        val post = db.posts.insert(NewPost(board, "guest:01abc", "t", "b", PostStatus.PUBLISHED, emptyList(), T0, null, "손님", "{bcrypt}h1"))
        assertEquals("손님", db.posts.find(post.id)!!.guestName)
        assertEquals("{bcrypt}h1", db.posts.guestSecretHash(post.id))
        assertEquals("손님", db.posts.page(PostQuery(board, 0, 10, PostSort.LATEST)).values.single().guestName)
        assertNull(db.newPost(board).let { db.posts.guestSecretHash(it.id) }, "a member row has no hash")
        assertNull(db.posts.guestSecretHash(-1))

        val c = db.comments.insert(NewComment(post.id, null, null, 0, "guest:02def", "c", T0, guestName = "나그네", guestSecretHash = "{sha256}h2"))!!
        assertEquals("나그네", db.comments.find(c.id)!!.guestName)
        assertEquals("{sha256}h2", db.comments.guestSecretHash(c.id))
        assertEquals("나그네", db.comments.rootPage(CommentRootQuery(post.id, 0, 10, CommentSort.OLDEST)).values.single().guestName)
    }

    @Test
    fun `a pending comment is stored without counting and approval counts it once`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val pending = db.comments.insert(NewComment(post.id, null, null, 0, "guest:01p", "c", T0, status = CommentStatus.PENDING))!!
        assertEquals(CommentStatus.PENDING, pending.status)
        assertEquals(0L, db.posts.find(post.id)!!.commentCount)
        db.comments.setStatus(pending.id, CommentStatus.PUBLISHED, T0.plusSeconds(1))
        assertEquals(1L, db.posts.find(post.id)!!.commentCount)
        db.comments.setStatus(pending.id, CommentStatus.HIDDEN, T0.plusSeconds(2))
        assertEquals(0L, db.posts.find(post.id)!!.commentCount)
    }

    @Test
    fun `adoption never picks a guest row as an account`() {
        val board = db.newBoard()
        val post = db.posts.insert(NewPost(board, "guest:01zzz-adopt", "t", "b", PostStatus.PUBLISHED, emptyList(), T0, null, "g", "{bcrypt}x"))
        db.comments.insert(NewComment(post.id, null, null, 0, "guest:01zzz-adopt2", "c", T0, guestName = "g", guestSecretHash = "{bcrypt}y"))
        val pending = JdbcBoardAuthorAdoption(db.jdbc).pendingAccounts(100_000)
        assertFalse(pending.any { it.startsWith("guest:") })
    }
}
