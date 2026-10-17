package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.ApprovalMode
import dev.sumin.skeleton.board.BoardChange
import dev.sumin.skeleton.board.BoardSettings
import dev.sumin.skeleton.board.CommentRootQuery
import dev.sumin.skeleton.board.CommentSort
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.GuestWrite
import dev.sumin.skeleton.board.ModeratedBy
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.NewPost
import dev.sumin.skeleton.board.PendingVisibility
import dev.sumin.skeleton.board.PostChange
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 승인 대기 · 글 주인 관리의 SQL: 설정 열, 보는 사람별 PENDING 걸러내기(쪽 수 포함), 대기 목록, 바꾼 쪽 기록 */
class JdbcModerationDbTest {
    private val db = BoardDb

    @Test
    fun `board settings round-trip and a partial update keeps the rest`() {
        val code = "m-${System.nanoTime() % 1_000_000}"
        val all = BoardSettings(GuestWrite.ALL, ApprovalMode.GUESTS, ApprovalMode.ALL, ownerModeratesComments = true)
        db.boards.create(code, "Moderated", null, T0, all)
        assertEquals(all, db.boards.find(code)!!.settings)
        val plain = db.newBoard()
        assertEquals(BoardSettings(), db.boards.find(plain)!!.settings, "boards made the old way read as everything off")

        val changed = db.boards.update(code, BoardChange(name = "Renamed", settings = all.copy(postApproval = ApprovalMode.OFF)))!!
        assertEquals("Renamed", changed.name)
        assertEquals(all.copy(postApproval = ApprovalMode.OFF), changed.settings)
        val nameOnly = db.boards.update(code, BoardChange(description = "d"))!!
        assertEquals("d", nameOnly.description)
        assertEquals("Renamed", nameOnly.name)
        assertEquals(all.copy(postApproval = ApprovalMode.OFF), nameOnly.settings)
        assertNull(db.boards.update("no-such-board", BoardChange(name = "x")))
    }

    @Test
    fun `waiting comments reach only the viewers the query names, and the totals agree`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val published = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "공개", T0))!!
        val mineWaiting = db.comments.insert(NewComment(post.id, null, null, 0, "acc_me", "내 대기", T0.plusSeconds(1), status = CommentStatus.PENDING))!!
        val theirsWaiting = db.comments.insert(NewComment(post.id, null, null, 0, "guest:01g", "남의 대기", T0.plusSeconds(2), status = CommentStatus.PENDING))!!
        fun ids(pending: PendingVisibility) =
            db.comments.rootPage(CommentRootQuery(post.id, 0, 10, CommentSort.OLDEST, pending)).let { page -> page.values.map { it.id } to page.totalElements }

        assertEquals(listOf(published.id) to 1L, ids(PendingVisibility.NONE))
        assertEquals(listOf(published.id, mineWaiting.id) to 2L, ids(PendingVisibility(authorId = "acc_me")))
        assertEquals(listOf(published.id, mineWaiting.id, theirsWaiting.id) to 3L, ids(PendingVisibility(all = true)))
        assertEquals(1L, db.posts.find(post.id)!!.commentCount, "waiting comments are not counted")
    }

    @Test
    fun `a never-public comment stays out of the public page after a rejection, and first publication is remembered`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val everPublic = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "공개", T0))!!
        val rejected = db.comments.insert(NewComment(post.id, null, null, 0, "acc_me", "거절", T0.plusSeconds(1), status = CommentStatus.PENDING))!!
        assertFalse(rejected.everPublished)
        assertTrue(everPublic.everPublished)
        db.comments.setStatus(rejected.id, CommentStatus.HIDDEN, T0.plusSeconds(2), ModeratedBy.OWNER)
        assertFalse(db.comments.find(rejected.id)!!.everPublished, "rejecting does not make it public")
        fun ids(p: PendingVisibility) = db.comments.rootPage(CommentRootQuery(post.id, 0, 10, CommentSort.OLDEST, p)).let { it.values.map { c -> c.id } to it.totalElements }
        assertEquals(listOf(everPublic.id) to 1L, ids(PendingVisibility.NONE))
        assertEquals(listOf(everPublic.id, rejected.id) to 2L, ids(PendingVisibility(authorId = "acc_me")))
        assertEquals(listOf(everPublic.id, rejected.id) to 2L, ids(PendingVisibility(all = true)))
        db.comments.setStatus(everPublic.id, CommentStatus.HIDDEN, T0.plusSeconds(3))
        assertEquals(listOf(everPublic.id) to 1L, ids(PendingVisibility.NONE), "a once-public comment keeps its placeholder")
        db.comments.setStatus(rejected.id, CommentStatus.PUBLISHED, T0.plusSeconds(4))
        db.comments.setStatus(rejected.id, CommentStatus.HIDDEN, T0.plusSeconds(5))
        assertTrue(db.comments.find(rejected.id)!!.everPublished)
    }

    @Test
    fun `the hiding side is only rewritten when the status really changes`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val c = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "c", T0))!!
        db.comments.setStatus(c.id, CommentStatus.HIDDEN, T0.plusSeconds(1), ModeratedBy.MODERATOR)
        assertEquals(ModeratedBy.MODERATOR, db.comments.setStatus(c.id, CommentStatus.HIDDEN, T0.plusSeconds(2), ModeratedBy.OWNER)!!.moderatedBy)
    }

    @Test
    fun `pendingPage lists only the waiting comments of that post, oldest first, with paging`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val other = db.newPost(board)
        val first = db.comments.insert(NewComment(post.id, null, null, 0, "guest:01a", "1", T0, status = CommentStatus.PENDING))!!
        val second = db.comments.insert(NewComment(post.id, null, null, 0, "guest:01b", "2", T0.plusSeconds(1), status = CommentStatus.PENDING))!!
        db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "공개", T0.plusSeconds(2)))
        db.comments.insert(NewComment(other.id, null, null, 0, "guest:01c", "다른 글", T0, status = CommentStatus.PENDING))
        val page = db.comments.pendingPage(post.id, 0, 10)
        assertEquals(listOf(first.id, second.id), page.values.map { it.id })
        assertEquals(2L, page.totalElements)
        assertEquals(listOf(second.id), db.comments.pendingPage(post.id, 1, 1).values.map { it.id })
    }

    @Test
    fun `the side that last changed a comment is recorded, and a plain status change keeps it`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val c = db.comments.insert(NewComment(post.id, null, null, 0, "acc_a", "c", T0))!!
        assertNull(db.comments.find(c.id)!!.moderatedBy)
        assertEquals(ModeratedBy.OWNER, db.comments.setStatus(c.id, CommentStatus.HIDDEN, T0.plusSeconds(1), ModeratedBy.OWNER)!!.moderatedBy)
        assertEquals(ModeratedBy.MODERATOR, db.comments.setStatus(c.id, CommentStatus.PUBLISHED, T0.plusSeconds(2), ModeratedBy.MODERATOR)!!.moderatedBy)
        assertEquals(ModeratedBy.MODERATOR, db.comments.setStatus(c.id, CommentStatus.DELETED, T0.plusSeconds(3))!!.moderatedBy, "the author's own delete does not rewrite it")
    }

    @Test
    fun `a post stores its comment approval and who last moderated it`() {
        val board = db.newBoard()
        val post = db.posts.insert(NewPost(board, "acc_a", "t", "b", PostStatus.PENDING, emptyList(), T0, commentApproval = ApprovalMode.ALL))
        assertEquals(PostStatus.PENDING, db.posts.find(post.id)!!.status)
        assertEquals(ApprovalMode.ALL, db.posts.find(post.id)!!.commentApproval)
        val approved = db.posts.update(post.id, PostChange(status = PostStatus.PUBLISHED, moderatedBy = ModeratedBy.MODERATOR), T0.plusSeconds(1))!!
        assertEquals(PostStatus.PUBLISHED, approved.status)
        assertEquals(ModeratedBy.MODERATOR, approved.moderatedBy)
        assertEquals(ApprovalMode.ALL, approved.commentApproval, "a status change leaves the comment approval alone")
        assertEquals(ApprovalMode.GUESTS, db.posts.update(post.id, PostChange(commentApproval = ApprovalMode.GUESTS), T0.plusSeconds(2))!!.commentApproval)
    }
}
