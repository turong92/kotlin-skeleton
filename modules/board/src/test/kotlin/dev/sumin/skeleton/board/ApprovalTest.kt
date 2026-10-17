package dev.sumin.skeleton.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 승인 대기 · 글 주인의 댓글 관리 (스펙 §3.1–3.4 · §10): 승인 전 노출, 주인 권한 넘기, 승인 순간의 개수 · 알림 */
class ApprovalTest {
    private val guestOn = BoardProperties(guest = BoardProperties.Guest(enabled = true))
    private val created = mutableListOf<BoardCommentNotice>()
    private val pending = mutableListOf<BoardCommentNotice>()
    private val events = mutableListOf<BoardEvent>()
    private val notifier = object : BoardNotifier {
        override fun commentCreated(notice: BoardCommentNotice) { created += notice }
        override fun commentPending(notice: BoardCommentNotice) { pending += notice }
    }

    private fun harness(settings: BoardSettings) =
        ServiceHarness(props = guestOn, settings = settings, notifier = notifier, listeners = listOf(BoardEventListener { events += it }))

    private val guestsApproved = BoardSettings(
        guestWrite = GuestWrite.ALL, postApproval = ApprovalMode.GUESTS, commentApproval = ApprovalMode.GUESTS, ownerModeratesComments = true,
    )
    private var addresses = 0
    private fun ctx(secret: String? = "pass1234") = GuestContext(secret, "ip-${addresses++}", "10.0.0.1")

    private fun ServiceHarness.guestComment(postId: Long, secret: String = "pass1234", parent: Long? = null) =
        commentService.create(null, "general", postId, parent, "손님 댓글", guestName = "손님", guest = ctx(secret))

    private fun ServiceHarness.threads(caller: BoardCaller?, post: Long) = commentService.list(caller, "general", post, 0, 20, CommentSort.OLDEST)

    // ---- 승인 전 노출 ----

    @Test
    fun `a pending comment has no place in the thread for the public, other members, or a post author who does not manage`() {
        val h = harness(guestsApproved)
        val post = h.newPost(owner).id
        val visible = h.commentService.create(other, "general", post, null, "공개 댓글")
        val pendingRoot = h.guestComment(post).comment
        h.guestComment(post, parent = visible.comment.id)
        assertEquals(CommentStatus.PENDING, pendingRoot.status)
        assertEquals(1L, h.store.posts.getValue(post).commentCount, "only the published comment counts")

        for (viewer in listOf(null, other, BoardCaller("acc_third"))) {
            val page = h.threads(viewer, post)
            assertEquals(listOf(visible.comment.id), page.values.map { it.comment.id }, "no slot for the waiting root, viewer=$viewer")
            assertEquals(1L, page.totalElements)
            val root = page.values.single()
            assertEquals(0, root.replyCount, "the waiting reply is not even counted, viewer=$viewer")
            assertTrue(root.replies.isEmpty())
        }

        val all = ServiceHarness(props = guestOn, settings = guestsApproved.copy(ownerModeratesComments = false))
        val p2 = all.newPost(owner).id
        all.guestComment(p2)
        assertTrue(all.threads(owner, p2).values.isEmpty(), "a post author without the management setting sees nothing waiting")
    }

    @Test
    fun `moderators, the managing post owner and the writing member see waiting comments with their body`() {
        val settings = guestsApproved.copy(commentApproval = ApprovalMode.ALL)
        val h = harness(settings)
        val post = h.newPost(owner).id
        val byMember = h.commentService.create(other, "general", post, null, "회원 대기 댓글").comment
        assertEquals(CommentStatus.PENDING, byMember.status)
        val byOwner = h.commentService.create(owner, "general", post, null, "주인 댓글").comment
        assertEquals(CommentStatus.PUBLISHED, byOwner.status, "the managing owner is not subject to approval on their own post")
        val byGuest = h.guestComment(post).comment

        for (viewer in listOf(moderator, owner)) {
            val page = h.threads(viewer, post)
            assertEquals(setOf(byMember.id, byOwner.id, byGuest.id), page.values.map { it.comment.id }.toSet(), "viewer=${viewer.accountId}")
            assertEquals("회원 대기 댓글", page.values.first { it.comment.id == byMember.id }.body)
        }
        val mine = h.threads(other, post)
        assertEquals(setOf(byMember.id, byOwner.id), mine.values.map { it.comment.id }.toSet(), "the writer sees their own waiting comment, not the guest's")
        assertEquals("회원 대기 댓글", mine.values.first { it.comment.id == byMember.id }.body)
        assertTrue(mine.values.first { it.comment.id == byMember.id }.mine)
    }

    @Test
    fun `a waiting post is 404 and unlisted for everyone but moderators and its member author`() {
        val h = harness(guestsApproved.copy(postApproval = ApprovalMode.ALL))
        val member = h.newPost(other)
        assertEquals(PostStatus.PENDING, member.status)
        val guest = h.postService.create(null, "general", CreatePost("손님 글", "본문", null, null, guestName = "손님"), ctx())
        assertEquals(PostStatus.PENDING, guest.post.status)
        val mod = h.newPost(moderator)
        assertEquals(PostStatus.PUBLISHED, mod.status, "moderators are never subject to approval")

        for (viewer in listOf(null, BoardCaller("acc_third"))) {
            assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.get(viewer, "general", member.id) })
            assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.get(viewer, "general", guest.post.id) })
            assertEquals(listOf(mod.id), h.postService.list(viewer, "general", PostListRequest(0, 20)).values.map { it.item.id })
        }
        assertEquals(member.id, h.postService.get(other, "general", member.id).post.id)
        assertEquals(BoardErrorCode.POST_NOT_FOUND, h.failure { h.postService.get(other, "general", guest.post.id) })
        assertEquals(listOf(member.id), h.postService.list(other, "general", PostListRequest(0, 20, mine = true)).values.filter { it.item.status == PostStatus.PENDING }.map { it.item.id })
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.postService.list(other, "general", PostListRequest(0, 20, status = PostStatus.PENDING)) })
        assertEquals(setOf(member.id, guest.post.id), h.postService.list(moderator, "general", PostListRequest(0, 20, status = PostStatus.PENDING)).values.map { it.item.id }.toSet())

        h.postService.moderate(moderator, "general", guest.post.id, PostStatus.PUBLISHED, null)
        assertEquals(guest.post.id, h.postService.get(null, "general", guest.post.id).post.id)
    }

    @Test
    fun `publishing a draft is a new post - it waits when the writer needs approval`() {
        val h = harness(guestsApproved.copy(postApproval = ApprovalMode.ALL))
        val draft = h.newPost(other, status = PostStatus.DRAFT)
        assertEquals(PostStatus.DRAFT, draft.status)
        val updated = h.postService.update(other, "general", draft.id, UpdatePost(null, null, null, PostStatus.PUBLISHED))
        assertEquals(PostStatus.PENDING, updated.post.status)
        val edited = h.postService.update(other, "general", draft.id, UpdatePost("고침", null, null, null))
        assertEquals(PostStatus.PENDING, edited.post.status, "editing a waiting post edits it in place")
        assertEquals("고침", edited.post.title)
    }

    // ---- 승인 순간 ----

    @Test
    fun `approval publishes the comment once - count plus one, one notice, one status event - and the pending notice goes to the owner only`() {
        val h = harness(guestsApproved)
        val post = h.newPost(owner).id
        val waiting = h.guestComment(post).comment
        assertEquals(0, created.size, "no comment.created while it waits")
        assertEquals(listOf("acc_author"), pending.map { it.recipientId })
        assertEquals(0L, h.store.posts.getValue(post).commentCount)

        events.clear()
        h.commentService.moderate(owner, "general", post, waiting.id, CommentStatus.PUBLISHED)
        assertEquals(1L, h.store.posts.getValue(post).commentCount)
        assertEquals(0, created.count { it.recipientId != "acc_author" })
        assertEquals(listOf("acc_author"), created.map { it.recipientId }, "the post owner is told once, at approval")
        val change = events.single() as CommentStatusChanged
        assertEquals(CommentStatus.PENDING to CommentStatus.PUBLISHED, change.from to change.to)
        assertEquals(BoardActorKind.OWNER, change.actor.kind)

        h.commentService.moderate(owner, "general", post, waiting.id, CommentStatus.PUBLISHED)
        assertEquals(1, created.size, "approving again changes nothing and tells nobody")
        assertEquals(1L, h.store.posts.getValue(post).commentCount)
    }

    @Test
    fun `a comment that needs no approval is published and notified right away, and pending notices need owner management`() {
        val h = harness(BoardSettings(guestWrite = GuestWrite.ALL))
        val post = h.newPost(owner).id
        h.commentService.create(other, "general", post, null, "바로 공개")
        assertEquals(1, created.size)
        assertEquals(0, pending.size)

        val noOwner = harness(guestsApproved.copy(ownerModeratesComments = false))
        val p2 = noOwner.newPost(owner).id
        noOwner.guestComment(p2)
        assertEquals(0, pending.size, "without owner management nobody has an inbox for waiting comments")
    }

    // ---- 주인 권한 넘기 ----

    @Test
    fun `a post owner manages only their own post's comments and only where the board allows it`() {
        val h = harness(guestsApproved)
        val theirs = h.newPost(other).id
        val onTheirs = h.guestComment(theirs).comment
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.moderate(owner, "general", theirs, onTheirs.id, CommentStatus.PUBLISHED) })
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.listPending(owner, "general", theirs, 0, 20) })
        assertEquals(CommentStatus.PENDING, h.store.comments.getValue(onTheirs.id).status)

        val closed = harness(guestsApproved.copy(ownerModeratesComments = false))
        val post = closed.newPost(owner).id
        val c = closed.guestComment(post).comment
        assertEquals(BoardErrorCode.FORBIDDEN, closed.failure { closed.commentService.moderate(owner, "general", post, c.id, CommentStatus.PUBLISHED) })
    }

    @Test
    fun `an owner can approve and hide but not delete, and cannot bring back what a moderator hid`() {
        val h = harness(guestsApproved)
        val post = h.newPost(owner).id
        val c = h.commentService.create(other, "general", post, null, "공개").comment
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.moderate(owner, "general", post, c.id, CommentStatus.DELETED) })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.commentService.moderate(owner, "general", post, c.id, CommentStatus.PENDING) })

        h.commentService.moderate(owner, "general", post, c.id, CommentStatus.HIDDEN)
        assertEquals(ModeratedBy.OWNER, h.store.comments.getValue(c.id).moderatedBy)
        h.commentService.moderate(owner, "general", post, c.id, CommentStatus.PUBLISHED)   // 자기가 가린 것은 되살린다

        h.commentService.moderate(moderator, "general", post, c.id, CommentStatus.HIDDEN)
        assertEquals(ModeratedBy.MODERATOR, h.store.comments.getValue(c.id).moderatedBy)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.moderate(owner, "general", post, c.id, CommentStatus.PUBLISHED) })
        assertEquals(CommentStatus.HIDDEN, h.store.comments.getValue(c.id).status)
        h.commentService.moderate(moderator, "general", post, c.id, CommentStatus.PUBLISHED)   // 운영자는 되살린다
    }

    @Test
    fun `a signed-out post owner approves with the post's secret, and the secret of another post is worthless`() {
        val h = harness(guestsApproved.copy(postApproval = ApprovalMode.OFF))
        val ownPost = h.postService.create(null, "general", CreatePost("내 글", "본문", null, null, guestName = "주인"), ctx("owner-secret")).post.id
        val otherPost = h.postService.create(null, "general", CreatePost("남의 글", "본문", null, null, guestName = "남"), ctx("other-secret")).post.id
        val c = h.guestComment(ownPost).comment
        val d = h.guestComment(otherPost).comment
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.moderate(null, "general", ownPost, c.id, CommentStatus.PUBLISHED, ctx("wrong-secret")) })
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.moderate(null, "general", otherPost, d.id, CommentStatus.PUBLISHED, ctx("owner-secret")) })
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.moderate(null, "general", ownPost, c.id, CommentStatus.PUBLISHED, ctx(null)) })
        assertEquals(listOf(c.id), h.commentService.listPending(null, "general", ownPost, 0, 20, ctx("owner-secret")).values.map { it.comment.id })

        events.clear()
        h.commentService.moderate(null, "general", ownPost, c.id, CommentStatus.PUBLISHED, ctx("owner-secret"))
        assertEquals(CommentStatus.PUBLISHED, h.store.comments.getValue(c.id).status)
        assertEquals(CommentStatus.PENDING, h.store.comments.getValue(d.id).status)
        assertEquals(BoardActorKind.OWNER, events.single().actor.kind)
    }

    @Test
    fun `the pending list is for managers only and runs oldest first`() {
        val h = harness(guestsApproved)
        val post = h.newPost(owner).id
        val first = h.guestComment(post).comment
        val second = h.guestComment(post).comment
        assertEquals(listOf(first.id, second.id), h.commentService.listPending(moderator, "general", post, 0, 20).values.map { it.comment.id })
        assertEquals(2L, h.commentService.listPending(owner, "general", post, 0, 20).totalElements)
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.commentService.listPending(other, "general", post, 0, 20) })
        assertEquals(listOf("손님 댓글", "손님 댓글"), h.commentService.listPending(moderator, "general", post, 0, 20).values.map { it.body })
    }

    // ---- 글마다 규칙 · 설정 고치기 ----

    @Test
    fun `a post can pick its own comment approval only on boards where owners manage comments`() {
        val off = harness(BoardSettings(guestWrite = GuestWrite.ALL))
        assertEquals(BoardErrorCode.CONTENT_INVALID, off.failure {
            off.postService.create(owner, "general", CreatePost("t", "b", null, null, commentApproval = ApprovalMode.ALL))
        })
        val h = harness(BoardSettings(guestWrite = GuestWrite.ALL, ownerModeratesComments = true))
        val strict = h.postService.create(owner, "general", CreatePost("t", "b", null, null, commentApproval = ApprovalMode.ALL)).post
        assertEquals(ApprovalMode.ALL, strict.commentApproval)
        assertEquals(CommentStatus.PENDING, h.commentService.create(other, "general", strict.id, null, "x").comment.status)
        assertEquals(CommentStatus.PUBLISHED, h.commentService.create(owner, "general", strict.id, null, "주인").comment.status)
        val relaxed = h.newPost(owner)
        assertEquals(CommentStatus.PUBLISHED, h.commentService.create(other, "general", relaxed.id, null, "x").comment.status, "the board value applies when the post says nothing")
        assertEquals(BoardErrorCode.CONTENT_INVALID, off.failure {
            off.postService.update(owner, "general", off.newPost(owner).id, UpdatePost(null, null, null, null, ApprovalMode.GUESTS))
        })
    }

    @Test
    fun `needsApproval is decided in one policy method - OFF GUESTS ALL by writer`() {
        val policy = DefaultBoardPolicy()
        fun board(mode: ApprovalMode, owners: Boolean = false) = aBoard.copy(settings = BoardSettings(postApproval = mode, commentApproval = mode, ownerModeratesComments = owners))
        val post = aPost(author = "acc_author")
        fun needs(mode: ApprovalMode, writer: BoardCaller?, target: ApprovalTarget, owners: Boolean = false) =
            policy.needsApproval(board(mode, owners), post.takeIf { target == ApprovalTarget.COMMENT }, writer, target)
        for (target in ApprovalTarget.entries) {
            assertFalse(needs(ApprovalMode.OFF, null, target))
            assertTrue(needs(ApprovalMode.GUESTS, null, target))
            assertFalse(needs(ApprovalMode.GUESTS, other, target))
            assertTrue(needs(ApprovalMode.ALL, null, target))
            assertTrue(needs(ApprovalMode.ALL, other, target))
            assertFalse(needs(ApprovalMode.ALL, moderator, target))
        }
        assertFalse(needs(ApprovalMode.ALL, owner, ApprovalTarget.COMMENT, owners = true), "a managing owner on their own post")
        assertTrue(needs(ApprovalMode.ALL, owner, ApprovalTarget.COMMENT, owners = false))
        assertTrue(needs(ApprovalMode.ALL, owner, ApprovalTarget.POST, owners = true), "owner management never exempts new posts")
    }

    @Test
    fun `only moderators edit board settings, and only the sent settings change`() {
        val h = harness(BoardSettings(guestWrite = GuestWrite.COMMENTS))
        assertEquals(BoardErrorCode.FORBIDDEN, h.failure { h.boardService.update(other, "general", UpdateBoard(name = "x")) })
        val updated = h.boardService.update(moderator, "general", UpdateBoard(settings = UpdateBoardSettings(commentApproval = ApprovalMode.GUESTS)))
        assertEquals(GuestWrite.COMMENTS, updated.settings.guestWrite)
        assertEquals(ApprovalMode.GUESTS, updated.settings.commentApproval)
        assertEquals("General", updated.name)
        assertEquals(BoardErrorCode.NOT_FOUND, h.failure { h.boardService.update(moderator, "nope", UpdateBoard(name = "x")) })
        assertEquals(BoardErrorCode.CONTENT_INVALID, h.failure { h.boardService.update(moderator, "general", UpdateBoard(name = "  ")) })
    }

    @Test
    fun `post detail tells who may manage comments and the old defaults need no approval at all`() {
        val h = harness(guestsApproved)
        val post = h.newPost(owner).id
        assertTrue(h.postService.get(owner, "general", post).canManageComments)
        assertTrue(h.postService.get(moderator, "general", post).canManageComments)
        assertFalse(h.postService.get(other, "general", post).canManageComments)
        assertFalse(h.postService.get(null, "general", post).canManageComments)

        val plain = ServiceHarness()   // 새 기능이 모두 꺼진 기본 게시판
        val p = plain.newPost(owner)
        assertEquals(PostStatus.PUBLISHED, p.status)
        assertEquals(CommentStatus.PUBLISHED, plain.commentService.create(other, "general", p.id, null, "c").comment.status)
        assertFalse(plain.postService.get(owner, "general", p.id).canManageComments, "owner management is off by default")
    }
}
