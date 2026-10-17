package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorIdentities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 댓글 첨부(스펙 §5 · §10): 기본 꺼짐, 가린 댓글의 사진은 나가지 않고, 판의 새 사진은 승인 전에 공개 응답에 없다 */
class CommentAttachmentTest {
    private val on = BoardProperties(commentMaxAttachments = 2, guest = BoardProperties.Guest(enabled = true))
    private fun harness(props: BoardProperties = on, settings: BoardSettings = BoardSettings(guestWrite = GuestWrite.ALL), identities: AuthorIdentities? = null) =
        ServiceHarness(props = props, settings = settings, identities = identities)

    private fun ServiceHarness.comment(post: Long, keys: List<String>?, who: BoardCaller = other) =
        commentService.create(who, "general", post, null, "사진", attachments = keys)

    @Test
    fun `comment attachments are off by default and a key is a 400`() {
        val h = ServiceHarness()
        val post = h.newPost(owner).id
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.comment(post, listOf("k/1.png")) })
        assertEquals(emptyList(), h.comment(post, null).attachments)
        assertEquals(0, h.commentService.list(null, "general", post, 0, 10, CommentSort.OLDEST).values.single().attachments.size)
    }

    @Test
    fun `with the limit on, keys are stored, listed for everyone, and capped`() {
        val h = harness()
        val post = h.newPost(owner).id
        val created = h.comment(post, listOf("k/1.png", "k/2.png"))
        assertEquals(listOf("k/1.png", "k/2.png"), created.attachments)
        assertEquals(listOf("k/1.png", "k/2.png"), h.commentService.list(null, "general", post, 0, 10, CommentSort.OLDEST).values.single().attachments)
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.comment(post, listOf("a", "b", "c")) })
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure {
            ServiceHarness(props = on, policy = object : BoardPolicy by DefaultBoardPolicy() {
                override fun canAttach(caller: BoardCaller, keys: List<String>) = false
            }).let { d -> d.comment(d.newPost(owner).id, listOf("k")) }
        })
    }

    @Test
    fun `a hidden or deleted comment shows no pictures and a waiting one only to those who can see it`() {
        val h = harness(settings = BoardSettings(guestWrite = GuestWrite.ALL, commentApproval = ApprovalMode.ALL, ownerModeratesComments = true))
        val post = h.newPost(owner).id
        val c = h.comment(post, listOf("k/waiting.png"), who = other).comment
        assertEquals(CommentStatus.PENDING, c.status)
        assertEquals(emptyList(), h.commentService.list(BoardCaller("acc_third"), "general", post, 0, 10, CommentSort.OLDEST).values.map { it.attachments }.flatten())
        assertEquals(listOf("k/waiting.png"), h.commentService.list(other, "general", post, 0, 10, CommentSort.OLDEST).values.single().attachments)
        assertEquals(listOf("k/waiting.png"), h.commentService.list(owner, "general", post, 0, 10, CommentSort.OLDEST).values.single().attachments)

        h.commentService.moderate(moderator, "general", post, c.id, CommentStatus.PUBLISHED)
        assertEquals(1, h.commentService.list(null, "general", post, 0, 10, CommentSort.OLDEST).values.single().attachments.size)
        h.commentService.moderate(moderator, "general", post, c.id, CommentStatus.HIDDEN)
        assertEquals(emptyList(), h.commentService.list(null, "general", post, 0, 10, CommentSort.OLDEST).values.single().attachments)
        h.commentService.delete(moderator, "general", post, c.id)
        assertEquals(emptyList(), h.commentService.moderate(moderator, "general", post, c.id, CommentStatus.DELETED).attachments)
    }

    @Test
    fun `an account id inside a key is refused where identities are active, guests are narrowed by canGuestAttach`() {
        val identities = AuthorIdentities { _, _, _ -> true }
        val h = harness(identities = identities)
        val post = h.newPostForced()
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.commentService.create(other, "general", post, null, "x", authorId = "prf_1", attachments = listOf("up/acc_other/1.png")) })

        val guestOnly = ServiceHarness(
            props = on, settings = BoardSettings(guestWrite = GuestWrite.ALL),
            policy = object : BoardPolicy by DefaultBoardPolicy() { override fun canGuestAttach(keys: List<String>) = keys.all { it.startsWith("guest-uploads/") } },
        )
        val p = guestOnly.newPost(owner).id
        val ctx = GuestContext("pass1234", "ip-a", "1.1.1.1")
        assertEquals(BoardErrorCode.FORBIDDEN, guestOnly.failure {
            guestOnly.commentService.create(null, "general", p, null, "x", guestName = "손님", guest = ctx, attachments = listOf("other/1.png"))
        })
        val ok = guestOnly.commentService.create(null, "general", p, null, "x", guestName = "손님", guest = GuestContext("pass1234", "ip-b", "1.1.1.1"), attachments = listOf("guest-uploads/1.png"))
        assertEquals(listOf("guest-uploads/1.png"), ok.attachments)
    }

    @Test
    fun `editing replaces the pictures and a waiting edit's new picture is not public until approved`() {
        val h = harness(settings = BoardSettings(guestWrite = GuestWrite.ALL, commentApproval = ApprovalMode.ALL, ownerModeratesComments = true))
        val post = h.newPost(owner).id
        val c = h.comment(post, listOf("k/old.png")).comment
        h.commentService.moderate(owner, "general", post, c.id, CommentStatus.PUBLISHED)

        val edited = h.commentService.update(other, "general", post, c.id, null, attachments = listOf("k/new.png"))
        assertEquals(listOf("k/old.png"), edited.attachments, "still the public pictures")
        assertEquals(listOf("k/new.png"), edited.pendingRevision!!.attachments)
        assertEquals("사진", edited.pendingRevision!!.body, "a pictures-only edit keeps the text")
        assertEquals(listOf("k/old.png"), h.commentService.list(BoardCaller("acc_third"), "general", post, 0, 10, CommentSort.OLDEST).values.single().attachments)

        val approved = h.commentService.decideRevision(owner, "general", post, c.id, RevisionDecision.APPROVE, h.v(RevisionTarget.COMMENT, c.id))
        assertEquals(listOf("k/new.png"), approved.attachments)
        assertTrue(h.store.revisions.isEmpty())
    }
}
