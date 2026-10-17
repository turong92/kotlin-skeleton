package dev.sumin.skeleton.board

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils

class BoardEventsTest {
    private val seen = mutableListOf<BoardEvent>()
    private val h = ServiceHarness(listeners = listOf(BoardEventListener { seen += it }))

    @AfterTest
    fun clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clear()
    }

    @Test
    fun `without a surrounding transaction the event goes out right after the write`() {
        val post = h.newPost()
        val created = seen.single()
        assertIs<PostCreated>(created)
        assertEquals(post.id, created.postId)
        assertEquals("general", created.boardCode)
        assertEquals(BoardActor(BoardActorKind.MEMBER, "acc_author"), created.actor)
    }

    @Test
    fun `inside a transaction the event waits for the commit and a rollback sends nothing`() {
        TransactionSynchronizationManager.initSynchronization()
        h.newPost()
        assertTrue(seen.isEmpty())
        TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations())
        assertEquals(1, seen.size)

        seen.clear()
        TransactionSynchronizationManager.clear()
        TransactionSynchronizationManager.initSynchronization()
        h.newPost()
        TransactionSynchronizationManager.clear()   // 롤백: afterCommit 을 부르지 않는다
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a listener that throws does not fail the request and the next listener still runs`() {
        val after = mutableListOf<BoardEvent>()
        val noisy = ServiceHarness(listeners = listOf(BoardEventListener { error("boom") }, BoardEventListener { after += it }))
        val post = noisy.newPost()
        assertEquals(PostStatus.PUBLISHED, post.status)
        assertEquals(1, after.size)
    }

    @Test
    fun `status changes carry from and to and the actor kind`() {
        val post = h.newPost()
        seen.clear()
        h.postService.moderate(moderator, "general", post.id, PostStatus.HIDDEN, null)
        val changed = seen.single()
        assertIs<PostStatusChanged>(changed)
        assertEquals(PostStatus.PUBLISHED, changed.from)
        assertEquals(PostStatus.HIDDEN, changed.to)
        assertEquals(BoardActorKind.MODERATOR, changed.actor.kind)
    }

    @Test
    fun `comment writes publish created updated and status events`() {
        val post = h.newPost()
        seen.clear()
        val c = h.commentService.create(other, "general", post.id, null, "hi")
        h.commentService.update(other, "general", post.id, c.comment.id, "hi2")
        h.commentService.delete(other, "general", post.id, c.comment.id)
        assertEquals(listOf("CommentCreated", "CommentUpdated", "CommentStatusChanged"), seen.map { it::class.simpleName })
        val deleted = seen.last() as CommentStatusChanged
        assertEquals(CommentStatus.PUBLISHED to CommentStatus.DELETED, deleted.from to deleted.to)
    }
}
