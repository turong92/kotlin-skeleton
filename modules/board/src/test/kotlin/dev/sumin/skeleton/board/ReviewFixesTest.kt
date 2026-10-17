package dev.sumin.skeleton.board

import dev.sumin.skeleton.board.notification.DefaultBoardNotificationFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 리뷰(묶음 2 · 확인) 지적의 회귀: 판 버전 · 겹침 · 승인을 끈 뒤 · 못 보는 행 · 첨부 0 · 알림 문구 · 쿼리 수 */
class ReviewFixesTest {
    private val settings = BoardSettings(
        guestWrite = GuestWrite.ALL, postApproval = ApprovalMode.ALL, commentApproval = ApprovalMode.ALL, ownerModeratesComments = true,
    )
    private val third = BoardCaller("acc_third")
    private val props = BoardProperties(guest = BoardProperties.Guest(enabled = true))
    private var addresses = 0
    private fun ctx(secret: String? = "pass1234") = GuestContext(secret, "ip-${addresses++}", "10.0.0.1")

    private fun publishedPost(h: ServiceHarness, author: BoardCaller = other): Post {
        val p = h.postService.create(author, "general", CreatePost("제목", "본문", null, null)).post
        return h.postService.moderate(moderator, "general", p.id, PostStatus.PUBLISHED, null).post
    }

    // ---- B1 판 버전 ----

    @Test
    fun `approving or rejecting an edit the writer overwrote meanwhile is a 409 and publishes nothing, the new version goes through`() {
        val h = ServiceHarness(props = props, settings = settings)
        val post = publishedPost(h)
        h.postService.update(other, "general", post.id, UpdatePost(null, "v1", null, null))
        val seen = h.v(RevisionTarget.POST, post.id)
        h.postService.update(other, "general", post.id, UpdatePost(null, "v2", null, null))
        assertEquals(BoardErrorCode.REVISION_CHANGED, h.failure { h.postService.decideRevision(moderator, "general", post.id, RevisionDecision.APPROVE, seen) })
        assertEquals("본문", h.store.posts.getValue(post.id).body, "v2 must not be published by an approval of v1")
        assertEquals(BoardErrorCode.REVISION_CHANGED, h.failure { h.postService.decideRevision(moderator, "general", post.id, RevisionDecision.REJECT, seen) })
        assertEquals(1, h.store.revisions.size)
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.postService.decideRevision(moderator, "general", post.id, RevisionDecision.APPROVE, null) })
        h.postService.decideRevision(moderator, "general", post.id, RevisionDecision.APPROVE, h.v(RevisionTarget.POST, post.id))
        assertEquals("v2", h.store.posts.getValue(post.id).body)
    }

    @Test
    fun `the same for a comment edit, and the writer's own response carries the stored version`() {
        val h = ServiceHarness(props = props, settings = settings)
        val post = publishedPost(h).id
        val c = h.commentService.create(third, "general", post, null, "공개").comment
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED)
        val first = h.commentService.update(third, "general", post, c.id, "v1").pendingRevision!!
        assertEquals(h.v(RevisionTarget.COMMENT, c.id), first.submittedAt, "the response carries the version the managers will see")
        h.commentService.update(third, "general", post, c.id, "v2")
        assertEquals(BoardErrorCode.REVISION_CHANGED, h.failure { h.commentService.decideRevision(other, "general", post, c.id, RevisionDecision.APPROVE, first.submittedAt) })
        assertEquals("공개", h.store.comments.getValue(c.id).body)
        assertEquals(BoardErrorCode.REVISION_CHANGED, h.failure { h.commentService.decideRevision(other, "general", post, c.id, RevisionDecision.REJECT, first.submittedAt) })
    }

    // ---- B2 겹침 ----

    @Test
    fun `an edit that lands after the target was hidden stores no revision and answers as not found`() {
        var hideFirst: (() -> Unit)? = null
        val h = ServiceHarness(
            props = props, settings = settings,
            wrapRevisions = { real ->
                object : BoardRevisionRepository by real {
                    override fun submit(revision: Revision): RevisionSubmit { hideFirst?.invoke(); return real.submit(revision) }
                }
            },
        )
        val post = publishedPost(h)
        hideFirst = { h.postService.moderate(moderator, "general", post.id, PostStatus.HIDDEN, null) }
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.update(other, "general", post.id, UpdatePost(null, "late", null, null)) })
        assertTrue(h.store.revisions.isEmpty(), "nothing is left on the hidden post")
        assertEquals("본문", h.store.posts.getValue(post.id).body)
    }

    // ---- 결정 14: 승인을 꺼도 대기는 대기 ----

    @Test
    fun `turning approval off leaves waiting posts and comments waiting - the writer cannot publish them, managers can`() {
        val h = ServiceHarness(props = props, settings = settings)
        val waiting = h.newPost(other)
        assertEquals(PostStatus.PENDING, waiting.status)
        val open = publishedPost(h, moderator)
        val comment = h.commentService.create(third, "general", open.id, null, "대기 댓글").comment
        assertEquals(CommentStatus.PENDING, comment.status)

        h.boardService.update(moderator, "general", UpdateBoard(settings = UpdateBoardSettings(postApproval = ApprovalMode.OFF, commentApproval = ApprovalMode.OFF)))
        assertEquals(PostStatus.PENDING, h.postService.update(other, "general", waiting.id, UpdatePost("고침", null, null, PostStatus.PUBLISHED)).post.status)
        assertEquals(CommentStatus.PENDING, h.store.comments.getValue(comment.id).status)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.moderate(third, "general", open.id, comment.id, CommentStatus.PUBLISHED) })

        h.postService.moderate(moderator, "general", waiting.id, PostStatus.PUBLISHED, null)
        h.commentService.moderate(moderator, "general", open.id, comment.id, CommentStatus.PUBLISHED)
        assertEquals(CommentStatus.PUBLISHED, h.store.comments.getValue(comment.id).status)
    }

    // ---- C2 반대쪽 ----

    @Test
    fun `a moderator deciding on what an owner hid takes the hide over`() {
        val h = ServiceHarness(props = props, settings = settings)
        val post = publishedPost(h).id
        val c = h.commentService.create(third, "general", post, null, "공개").comment
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED)
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.HIDDEN)
        assertEquals(ModeratedBy.OWNER, h.store.comments.getValue(c.id).moderatedBy)
        h.commentService.moderate(moderator, "general", post, c.id, CommentStatus.HIDDEN)
        assertEquals(ModeratedBy.MODERATOR, h.store.comments.getValue(c.id).moderatedBy)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED) })
    }

    // ---- 관리 가능 여부: 되살릴 수 없는 댓글에 단추를 그리지 않게 ----

    @Test
    fun `canManage tells an owner which comments the owner can still change, moderators always, and others nothing`() {
        val h = ServiceHarness(props = props, settings = settings.copy(commentApproval = ApprovalMode.OFF))
        val post = publishedPost(h).id   // 글 주인 = other(ownerModeratesComments)
        val ownerHid = h.commentService.create(third, "general", post, null, "a").comment
        val modHid = h.commentService.create(third, "general", post, null, "b").comment
        val shown = h.commentService.create(third, "general", post, null, "c").comment
        h.commentService.moderate(other, "general", post, ownerHid.id, CommentStatus.HIDDEN)
        h.commentService.moderate(moderator, "general", post, modHid.id, CommentStatus.HIDDEN)
        fun flags(who: BoardCaller?) = h.commentService.list(who, "general", post, 0, 10, CommentSort.OLDEST).values.associate { it.comment.id to it.canManage }
        assertEquals(mapOf(ownerHid.id to true, modHid.id to false, shown.id to true), flags(other))
        assertEquals(mapOf(ownerHid.id to true, modHid.id to true, shown.id to true), flags(moderator))
        assertEquals(mapOf(ownerHid.id to null, modHid.id to null, shown.id to null), flags(third))
        assertEquals(mapOf(ownerHid.id to null, modHid.id to null, shown.id to null), flags(null))
    }

    // ---- M-f 못 보는 행은 한 가지 답 ----

    @Test
    fun `a rejected never-public comment reads as not found for reactions, edits, deletes and guest tries alike`() {
        val h = ServiceHarness(props = props, settings = settings.copy(commentApproval = ApprovalMode.GUESTS))
        val post = publishedPost(h).id
        val guest = h.commentService.create(null, "general", post, null, "손님", guestName = "손님", guest = ctx("right-secret")).comment
        h.commentService.moderate(other, "general", post, guest.id, CommentStatus.HIDDEN)   // 거절
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { h.reactionService.reactToComment(third, "general", post, guest.id, "LIKE") })
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { h.reactionService.removeFromComment(third, "general", post, guest.id, null) })
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { h.commentService.update(third, "general", post, guest.id, "x") })
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { h.commentService.delete(third, "general", post, guest.id) })
        assertEquals(BoardErrorCode.COMMENT_NOT_FOUND, h.failure { h.commentService.update(null, "general", post, guest.id, "x", ctx("wrong-secret")) })
    }

    // ---- M-a · M-c 첨부 ----

    @Test
    fun `with comment pictures switched off an empty list leaves the stored pictures alone`() {
        val updates = mutableListOf<List<String>?>()
        val h = ServiceHarness(
            props = props, settings = settings.copy(commentApproval = ApprovalMode.GUESTS),   // 회원 수정은 바로 반영, 그림 상한 기본 0
            wrapComments = { real -> object : CommentRepository by real {
                override fun updateContent(id: Long, body: String, attachments: List<String>?, now: java.time.Instant): Comment? { updates += attachments; return real.updateContent(id, body, attachments, now) }
            } },
        )
        val post = publishedPost(h).id
        val c = h.commentService.create(third, "general", post, null, "공개").comment
        h.store.commentFiles[c.id] = listOf("k/old.png")   // 상한을 올려 두었을 때 저장된 그림
        h.commentService.update(third, "general", post, c.id, "직접 수정", attachments = emptyList())
        assertEquals(listOf<List<String>?>(null), updates, "an empty list is 'do not touch' when pictures are off")
        assertEquals(listOf("k/old.png"), h.store.commentFiles[c.id])
    }

    @Test
    fun `a text-only edit keeps the stored pictures through approval`() {
        val h = ServiceHarness(props = props, settings = settings)
        val post = publishedPost(h).id
        val c = h.commentService.create(third, "general", post, null, "공개").comment
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED)
        h.store.commentFiles[c.id] = listOf("k/old.png")
        h.commentService.update(third, "general", post, c.id, "고친 말")
        h.commentService.decideRevision(other, "general", post, c.id, RevisionDecision.APPROVE, h.v(RevisionTarget.COMMENT, c.id))
        assertEquals("고친 말", h.store.comments.getValue(c.id).body)
        assertEquals(listOf("k/old.png"), h.store.commentFiles[c.id], "approving a text-only edit must not wipe the stored pictures")
    }

    // ---- M-g 알림 문구 ----

    @Test
    fun `the edit notice carries the edited text, and the default message uses it instead of the old body`() {
        val notices = mutableListOf<BoardCommentNotice>()
        val h = ServiceHarness(props = props, settings = settings, notifier = object : BoardNotifier {
            override fun commentCreated(notice: BoardCommentNotice) = Unit
            override fun commentPending(notice: BoardCommentNotice) { notices += notice }
        })
        val post = publishedPost(h).id
        val c = h.commentService.create(third, "general", post, null, "옛 본문").comment
        h.commentService.moderate(other, "general", post, c.id, CommentStatus.PUBLISHED)
        notices.clear()
        h.commentService.update(third, "general", post, c.id, "고친 본문")
        val notice = notices.single()
        assertEquals("고친 본문", notice.revisionBody)
        assertEquals("고친 본문", DefaultBoardNotificationFormatter().format(notice).message)
    }
}
