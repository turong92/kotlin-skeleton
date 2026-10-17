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
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 공개 영역은 board 상태를 따라간다 - 공개된 것만 있고, 가리거나 지우거나 판으로 빠지면 사라진다. 사고 나는 쪽(공개 영역에 남음)을 본다 */
class JdbcPublicAttachmentDbTest {
    private val db = BoardDb
    private val repo = JdbcPublicAttachmentRepository(db.jdbc, db.tx, DbTestDatabase.dialect)
    private val revisions = JdbcBoardRevisionRepository(db.jdbc, db.tx, DbTestDatabase.dialect)

    /** 공개 영역을 흉내 낸다 - 객체는 비공개 키별로 하나 */
    private class FakeUploads : PublicUploads {
        val objects = mutableSetOf<String>()
        var failPublish: ((String) -> Unit)? = null
        var duringPublish: (() -> Unit)? = null
        var afterCopyTimeout = false
        val unpublishable = mutableSetOf<String>()
        override fun urlOf(privateKey: String) = "https://cdn/pub/$privateKey"
        override fun publicKeyOf(privateKey: String) = "pub/$privateKey"
        override fun publish(privateKey: String): Boolean {
            failPublish?.invoke(privateKey)
            if (privateKey in unpublishable) return false
            duringPublish?.invoke()
            objects += privateKey
            if (afterCopyTimeout) error("timeout after the copy went through")
            return true
        }
        override fun unpublish(privateKey: String) { objects -= privateKey }
        override fun listPublicKeys() = objects.map { "pub/$it" }
        override fun deletePublicKey(publicKey: String) { objects.removeIf { "pub/$it" == publicKey } }
    }

    private val uploads = FakeUploads()
    private var now: Instant = T0

    init {
        db.jdbc.update("delete from board_public_attachment_runs", emptyMap<String, Any>())   // 같은 DB 를 쓰는 다른 시험의 「방금 돌았다」를 지운다
    }
    private val sync = BoardPublicAttachments(repo, { uploads }, TimeProvider { now }, null, Duration.ofHours(24))

    /** 이 시험의 키 표지 - 같은 DB 의 다른 시험이 남긴 줄을 조정자가 함께 처리해도 이 시험의 단언은 자기 키만 본다 */
    private val tag = "t${System.nanoTime()}/"
    private fun mine(): Set<String> = uploads.objects.filter { it.startsWith(tag) }.map { it.removePrefix(tag) }.toSet()

    private fun comment(post: Long, status: CommentStatus, vararg keys: String) =
        db.comments.insert(NewComment(post, null, null, 0, "acc_a", "c", T0, status = status, attachments = keys.map { "$tag$it" }))!!

    @Test
    fun `a waiting comment's picture is not public, an approved one is, a hidden or deleted one is gone again`() {
        val post = db.newPost(db.newBoard())
        val c = comment(post.id, CommentStatus.PENDING, "a.png")
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(emptySet(), mine(), "waiting: private only")
        db.comments.setStatus(c.id, CommentStatus.PUBLISHED, T0)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(setOf("a.png"), mine())
        db.comments.setStatus(c.id, CommentStatus.HIDDEN, T0)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(emptySet(), mine(), "hidden: gone from the public area")
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
        val c = comment(post.id, CommentStatus.PUBLISHED, "old.png")
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        revisions.submit(Revision(RevisionTarget.COMMENT, c.id, board, post.id, null, "new text", listOf("${tag}new.png"), T0, T0))
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        now = now.plus(Duration.ofDays(2))
        sync.fullPass()
        assertEquals(setOf("old.png"), mine(), "the version's picture is not public before approval - not even for the full pass")
        revisions.apply(RevisionTarget.COMMENT, c.id, T0.plusSeconds(1), revisions.find(RevisionTarget.COMMENT, listOf(c.id)).getValue(c.id).updatedAt)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(setOf("new.png"), mine())
    }

    @Test
    fun `hiding the post takes its comments' pictures down, and a row deleted behind our back is caught by the full pass`() {
        val post = db.newPost(db.newBoard(), attachments = listOf("${tag}post.png"))
        val c = comment(post.id, CommentStatus.PUBLISHED, "c.png")
        sync.reconcile(RevisionTarget.POST, post.id)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(setOf("post.png", "c.png"), mine())
        db.posts.update(post.id, PostChange(status = PostStatus.HIDDEN), T0)
        sync.reconcilePost(post.id)
        assertEquals(emptySet(), mine())

        val other = db.newPost(db.newBoard())
        val gone = comment(other.id, CommentStatus.PUBLISHED, "erased.png")
        sync.reconcile(RevisionTarget.COMMENT, gone.id)
        db.jdbc.update("delete from board_comment_attachments where comment_id = :id", mapOf("id" to gone.id))   // 탈퇴 삭제 등 표를 직접 지운 경우
        now = now.plus(Duration.ofDays(2))
        assertTrue(sync.fullPass() > 0)
        assertEquals(emptySet(), mine(), "the full pass removes what no row justifies any more")
    }

    @Test
    fun `a failed copy is retried from the queue, and a key held by two comments stays while one of them still shows it`() {
        val post = db.newPost(db.newBoard())
        val a = comment(post.id, CommentStatus.PUBLISHED, "shared.png")
        val b = comment(post.id, CommentStatus.PUBLISHED, "shared.png")
        uploads.failPublish = { error("S3 down") }
        sync.reconcile(RevisionTarget.COMMENT, a.id)
        assertEquals(emptySet(), mine())
        uploads.failPublish = null
        assertTrue(sync.processQueue() >= 1, "the failed item was written down and is retried")
        assertEquals(setOf("shared.png"), mine())
        sync.reconcile(RevisionTarget.COMMENT, b.id)   // 둘 다 줄이 있다
        db.comments.setStatus(a.id, CommentStatus.HIDDEN, T0)
        sync.reconcile(RevisionTarget.COMMENT, a.id)
        assertEquals(setOf("shared.png"), mine(), "the other comment still shows it")
        db.comments.setStatus(b.id, CommentStatus.HIDDEN, T0)
        sync.reconcile(RevisionTarget.COMMENT, b.id)
        assertEquals(emptySet(), mine())
    }

    @Test
    fun `race A - hidden while the copy is going through - the copy is taken back at once`() {
        val post = db.newPost(db.newBoard())
        val c = comment(post.id, CommentStatus.PUBLISHED, "race.png")
        uploads.duringPublish = { db.comments.setStatus(c.id, CommentStatus.HIDDEN, T0) }
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(emptySet(), mine(), "eligibility is read again after the copy: a hide that landed in between is honoured")
        assertEquals(0L, db.scalar("select count(*) from board_public_attachments where target_id = :id", "id" to c.id))
    }

    @Test
    fun `timeout B - the copy went through but the call timed out - hiding later still removes the object`() {
        val post = db.newPost(db.newBoard())
        val c = comment(post.id, CommentStatus.PUBLISHED, "timeout.png")
        uploads.afterCopyTimeout = true
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        uploads.afterCopyTimeout = false
        assertEquals(setOf("timeout.png"), mine(), "the object exists although the call failed")
        assertEquals(1L, db.scalar("select count(*) from board_public_attachments where target_id = :id", "id" to c.id), "the note stays - never 'unmark' on a timeout")
        db.comments.setStatus(c.id, CommentStatus.HIDDEN, T0)
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(emptySet(), mine(), "the stale object is gone, not left forever")
    }

    @Test
    fun `an object no row points to is deleted by the full pass, and one a row points to is kept`() {
        val post = db.newPost(db.newBoard())
        val kept = comment(post.id, CommentStatus.PUBLISHED, "kept.png")
        sync.reconcile(RevisionTarget.COMMENT, kept.id)
        uploads.objects += "${tag}orphan.png"   // 줄 없이 남은 객체 (겹침 · 시간 초과의 흔적)
        now = now.plus(Duration.ofDays(2))
        sync.fullPass()
        assertEquals(setOf("kept.png"), mine())
    }

    @Test
    fun `the full pass runs once per interval on one instance, and a type that must not be public gets no copy`() {
        assertTrue(repo.tryStartRun("test-${System.nanoTime()}", T0, Duration.ofHours(24)), "first caller wins")
        val name = "test-${System.nanoTime()}"
        assertTrue(repo.tryStartRun(name, T0, Duration.ofHours(24)))
        assertFalse(repo.tryStartRun(name, T0.plusSeconds(60), Duration.ofHours(24)), "a second instance inside the interval does not run")
        assertTrue(repo.tryStartRun(name, T0.plus(Duration.ofHours(25)), Duration.ofHours(24)))

        val post = db.newPost(db.newBoard())
        val c = comment(post.id, CommentStatus.PUBLISHED, "notes.txt")
        uploads.unpublishable += "${tag}notes.txt"
        sync.reconcile(RevisionTarget.COMMENT, c.id)
        assertEquals(emptySet(), mine())
        assertEquals(0L, db.scalar("select count(*) from board_public_attachments where target_id = :id", "id" to c.id), "nothing is noted for a copy that was never made")
    }
}
