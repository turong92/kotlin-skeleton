package dev.sumin.skeleton.board.jdbc

import dev.sumin.skeleton.board.BoardPublicAttachments
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.PostChange
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.Revision
import dev.sumin.skeleton.board.RevisionTarget
import dev.sumin.skeleton.board.jdbc.BoardDb.T0
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.PublicUploads
import kotlin.test.Test
import kotlin.test.assertEquals

/** 공개 영역은 board 상태를 따라간다 - 공개된 것만 있고, 가리거나 지우거나 판으로 빠지면 사라진다. 사고 나는 쪽(공개 영역에 남음)을 본다 */
class JdbcPublicAttachmentDbTest {
    private val db = BoardDb
    private val repo = JdbcPublicAttachmentRepository(db.jdbc, DbTestDatabase.dialect)
    private val revisions = JdbcBoardRevisionRepository(db.jdbc, db.tx, DbTestDatabase.dialect)

    private class FakeUploads : PublicUploads {
        val live = mutableSetOf<String>()
        var failPublish = false
        override fun urlOf(privateKey: String) = "https://cdn/$privateKey"
        override fun publish(privateKey: String) { if (failPublish) error("S3 down"); live += privateKey }
        override fun unpublish(privateKey: String) { live -= privateKey }
    }

    private val uploads = FakeUploads()
    /** 이 시험의 키 표지 - 같은 DB 의 다른 시험이 남긴 줄을 조정자가 함께 처리해도 이 시험의 단언은 자기 키만 본다 */
    private val tag = "t${System.nanoTime()}/"
    private fun mine(): Set<String> = uploads.live.filter { it.startsWith(tag) }.map { it.removePrefix(tag) }.toSet()
    private val sync = BoardPublicAttachments(repo, { uploads }, TimeProvider.fixed(T0))

    private fun comment(post: Long, status: CommentStatus, vararg keys: String) =
        db.comments.insert(NewComment(post, null, null, 0, "acc_a", "c", T0, status = status, attachments = keys.toList()))!!

    @Test
    fun `a waiting comment's picture is not public, an approved one is, a hidden or deleted one is gone again`() {
        val post = db.newPost(db.newBoard())
        val c = comment(post.id, CommentStatus.PENDING, "${tag}a.png")
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(emptySet(), mine(), "waiting: private only")
        db.comments.setStatus(c.id, CommentStatus.PUBLISHED, T0)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(setOf("a.png"), mine())
        db.comments.setStatus(c.id, CommentStatus.HIDDEN, T0)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(emptySet(), mine(), "hidden: gone from the public area")
        assertEquals(0L, db.scalar("select count(*) from board_public_attachments"))
        db.comments.setStatus(c.id, CommentStatus.PUBLISHED, T0)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        db.comments.setStatus(c.id, CommentStatus.DELETED, T0)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(emptySet(), mine(), "deleted: gone")
    }

    @Test
    fun `an edit version's picture stays private until the edit is approved, and the dropped picture leaves`() {
        val board = db.newBoard()
        val post = db.newPost(board)
        val c = comment(post.id, CommentStatus.PUBLISHED, "${tag}old.png")
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        revisions.submit(Revision(RevisionTarget.COMMENT, c.id, board, post.id, null, "new text", listOf("${tag}new.png"), T0, T0))
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(setOf("old.png"), mine(), "the version's picture is not public before approval")
        sync.sweep()
        assertEquals(setOf("old.png"), mine(), "nor does the sweep publish it")
        revisions.apply(RevisionTarget.COMMENT, c.id, T0.plusSeconds(1), revisions.find(RevisionTarget.COMMENT, listOf(c.id)).getValue(c.id).updatedAt)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(setOf("new.png"), mine())
    }

    @Test
    fun `hiding the post takes its comments' pictures down, and a row deleted behind our back is swept`() {
        val post = db.newPost(db.newBoard(), attachments = listOf("${tag}post.png"))
        val c = comment(post.id, CommentStatus.PUBLISHED, "${tag}c.png")
        sync.reconcile(RevisionTarget.POST, post.id)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(setOf("post.png", "c.png"), mine())
        db.posts.update(post.id, PostChange(status = PostStatus.HIDDEN), T0)
        sync.reconcilePost(post.id)
        assertEquals(emptySet(), mine())

        val other = db.newPost(db.newBoard())
        val gone = comment(other.id, CommentStatus.PUBLISHED, "${tag}erased.png")
        sync.reconcile(RevisionTarget.COMMENT, gone.id)
        db.jdbc.update("delete from board_comment_attachments where comment_id = :id", mapOf("id" to gone.id))   // 탈퇴 삭제 등 표를 직접 지운 경우
        assertEquals(true, sync.sweep() > 0)
        assertEquals(emptySet(), mine(), "the sweep removes what no row justifies any more")
    }

    @Test
    fun `a failed copy is retried by the sweep, and a key held by two comments stays while one of them still shows it`() {
        val post = db.newPost(db.newBoard())
        val a = comment(post.id, CommentStatus.PUBLISHED, "${tag}shared.png")
        val b = comment(post.id, CommentStatus.PUBLISHED, "${tag}shared.png")
        uploads.failPublish = true
        sync.reconcile(RevisionTarget.COMMENT, a.id)
        assertEquals(0L, db.scalar("select count(*) from board_public_attachments where target_id = :id", "id" to a.id), "no 'copied' note for a copy that failed")
        uploads.failPublish = false
        sync.sweep()
        assertEquals(setOf("shared.png"), mine())
        db.comments.setStatus(a.id, CommentStatus.HIDDEN, T0)
        sync.reconcile(RevisionTarget.COMMENT, a.id)
        assertEquals(setOf("shared.png"), mine(), "the other comment still shows it")
        db.comments.setStatus(b.id, CommentStatus.HIDDEN, T0)
        sync.reconcile(RevisionTarget.COMMENT, b.id)
        assertEquals(emptySet(), mine())
    }
}
