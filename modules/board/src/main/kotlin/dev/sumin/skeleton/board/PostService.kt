package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant

class PostService(
    private val access: BoardAccess,
    private val posts: PostRepository,
    private val reactions: ReactionSupport,
    private val authors: AuthorNames,
    private val policy: BoardPolicy,
    private val rules: BoardContentRules,
    private val properties: BoardProperties,
    private val limiter: BoardRateLimiter,
    private val time: TimeProvider,
    private val authorRules: BoardAuthorRules,
    private val events: BoardEvents = BoardEvents.NONE,
    private val guests: BoardGuests = BoardGuests(properties),
    private val revisions: BoardRevisionRepository = NoBoardRevisions,
) {
    /** [caller] 가 null 이면 로그인하지 않은 쓰기 — [guest] 가 있고 게시판이 허락하면 비회원 글, 아니면 401 */
    fun create(caller: BoardCaller?, boardCode: String, request: CreatePost, guest: GuestContext? = null): PostDetailView {
        val board = access.board(boardCode)
        if (caller == null) return createAsGuest(board, request, guest)
        if (request.guestName != null) invalid("guestName is for signed-out writers only")
        if (!policy.canCreatePost(caller, board)) forbidden("Not allowed to post in $boardCode")
        val authorRef = authorRules.authorRefFor(caller, request.authorId, boardCode)
        val status = request.status ?: PostStatus.PUBLISHED
        if (status !in WRITABLE_STATUSES) invalid("status must be DRAFT or PUBLISHED")
        val title = rules.title(request.title)
        val body = rules.postBody(request.body)
        val attachments = rules.attachments(request.attachments)
        authorRules.rejectAccountIdKeys(caller, attachments)
        if (attachments.isNotEmpty() && !policy.canAttach(caller, attachments)) forbidden("Not allowed to attach these files")
        val commentApproval = request.commentApproval?.also { requireOwnerModerates(board) }
        rateLimit(caller)
        val stored = if (status == PostStatus.PUBLISHED && policy.needsApproval(board, null, caller, ApprovalTarget.POST)) PostStatus.PENDING else status
        val post = posts.insert(NewPost(boardCode, caller.accountId, title, body, stored, attachments, time.now(), authorRef, commentApproval = commentApproval))
        events.publish(PostCreated(post, actorOf(caller)))
        return detail(caller, post)
    }

    private fun createAsGuest(board: Board, request: CreatePost, guest: GuestContext?): PostDetailView {
        val ctx = guestWriter(board, guest)
        if (!policy.canGuestPost(board)) forbidden("Guests cannot post in ${board.code}")
        if (request.authorId != null) throw BoardException(BoardErrorCode.AUTHOR_NOT_ALLOWED, "Not allowed to write as this author")
        if ((request.status ?: PostStatus.PUBLISHED) != PostStatus.PUBLISHED) invalid("guest posts cannot be drafts")
        if (request.commentApproval != null) invalid("guests cannot choose how comments on their post are approved")
        val status = if (policy.needsApproval(board, null, null, ApprovalTarget.POST)) PostStatus.PENDING else PostStatus.PUBLISHED
        val title = rules.title(request.title)
        val body = rules.postBody(request.body)
        val attachments = rules.attachments(request.attachments)
        val name = guests.name(request.guestName, rules)
        val secret = guests.validateNewSecret(ctx)
        if (attachments.isNotEmpty() && !policy.canGuestAttach(attachments)) forbidden("Not allowed to attach these files")
        guests.admit(ctx, checkCaptcha = true)
        val post = posts.insert(
            NewPost(board.code, guests.newAuthorId(), title, body, status, attachments, time.now(), null, name, guests.hash(secret)),
        )
        events.publish(PostCreated(post, GUEST))
        return detail(null, post)
    }

    fun get(caller: BoardCaller?, boardCode: String, postId: Long): PostDetailView {
        val post = access.post(caller, boardCode, postId)
        posts.incrementViews(post.id)
        return detail(caller, post.copy(viewCount = post.viewCount + 1))
    }

    fun list(caller: BoardCaller?, boardCode: String, request: PostListRequest): PageResult<PostSummaryView> {
        if (request.revision) return listRevisions(caller, boardCode, request)
        access.board(boardCode)
        val reactionType = request.reaction?.let { reactions.requireType(it) }
        val (authorId, statuses) = scope(caller, request)
        val page = posts.page(
            PostQuery(
                boardCode = boardCode,
                page = request.page,
                size = properties.pageSize(request.size),
                sort = request.sort,
                reactionType = reactionType,
                search = rules.searchTerm(request.q),
                statuses = statuses,
                authorId = authorId,
                excerptLength = properties.excerptLength,
            ),
        )
        return PageResult(summaryViews(caller, boardCode, page.values), page.totalElements)
    }

    private fun summaryViews(caller: BoardCaller?, boardCode: String, items: List<PostListItem>, revs: Map<Long, Revision> = emptyMap()): List<PostSummaryView> {
        val states = reactions.states(ReactionTarget.POST, items.map { it.id }, caller)
        val active = authorRules.active
        val publicIds = items.associate { it.id to BoardAuthors.publicId(it.authorRef, it.authorId, BoardAuthors.POST_ROW, it.id, active) }
        val names = authors.of(boardCode, publicIds.values)
        return items.map {
            val publicId = publicIds.getValue(it.id)
            val card = if (BoardAuthors.isGuest(it.authorId)) AuthorCard(it.guestName) else names[publicId]
            PostSummaryView(it, states.getValue(it.id), publicId, BoardAuthors.isDeleted(it.authorId) || card?.deleted == true, caller?.accountId == it.authorId, card, revs[it.id]?.view())
        }
    }

    /** 고친 글 목록(운영자) — 공개본 요약에 대기 중인 판을 붙여 나란히 비교한다. 새 글 대기 목록은 `status=PENDING` */
    private fun listRevisions(caller: BoardCaller?, boardCode: String, request: PostListRequest): PageResult<PostSummaryView> {
        access.board(boardCode)
        if (caller == null || !policy.canModerate(caller)) forbidden("Only moderators list pending revisions")
        val page = revisions.pending(RevisionTarget.POST, boardCode, null, request.page, properties.pageSize(request.size))
        val items = page.values.mapNotNull { rev -> posts.find(rev.targetId)?.let { it.toListItem() } }
        return PageResult(summaryViews(caller, boardCode, items, page.values.associateBy { it.targetId }), page.totalElements)
    }

    private fun Post.toListItem() =
        PostListItem(id, boardCode, authorId, title, body.take(properties.excerptLength), status, pinned, viewCount, commentCount, reactionCount, attachments.size, createdAt, updatedAt, authorRef, guestName)

    /** 판 승인 · 거절(운영자). 승인이면 공개본이 바뀌므로 `PostUpdated` 도 나간다 */
    fun decideRevision(caller: BoardCaller, boardCode: String, postId: Long, decision: RevisionDecision, version: Instant?): PostDetailView {
        if (!policy.canModerate(caller)) forbidden("Only moderators decide revisions")
        val post = access.post(caller, boardCode, postId)
        val seen = version ?: invalid("submittedAt (the version of the edit you reviewed) is required")
        val actor = actorOf(caller)
        val none = BoardException(BoardErrorCode.POST_NOT_FOUND, "No pending revision for post $postId")
        val changed = BoardException(BoardErrorCode.REVISION_CHANGED, "The edit changed while you were reviewing it - review it again")
        if (decision == RevisionDecision.APPROVE) {
            when (revisions.apply(RevisionTarget.POST, post.id, time.now(), seen)) {
                RevisionWrite.NONE -> throw none
                RevisionWrite.CHANGED -> throw changed
                RevisionWrite.DONE -> Unit
            }
            val updated = posts.find(post.id) ?: notFound(postId)
            events.publish(PostUpdated(updated, actor))
            events.publish(RevisionDecided(RevisionTarget.POST, post.id, boardCode, post.id, RevisionOutcome.APPROVED, actor))
            return detail(caller, updated)
        }
        when (revisions.reject(RevisionTarget.POST, post.id, seen)) {
            RevisionWrite.NONE -> throw none
            RevisionWrite.CHANGED -> throw changed
            RevisionWrite.DONE -> Unit
        }
        events.publish(RevisionDecided(RevisionTarget.POST, post.id, boardCode, post.id, RevisionOutcome.REJECTED, actor))
        return detail(caller, post)
    }

    fun update(caller: BoardCaller?, boardCode: String, postId: Long, request: UpdatePost, guest: GuestContext? = null): PostDetailView {
        if (caller == null) return updateAsGuest(boardCode, postId, request, guest)
        val post = access.post(caller, boardCode, postId)
        if (!policy.canEditPost(caller, post)) forbidden("Not allowed to edit post $postId")
        request.status?.let { if (it !in WRITABLE_STATUSES) invalid("status must be DRAFT or PUBLISHED (moderators use /moderation)") }
        val attachments = request.attachments?.let { rules.attachments(it) }
        val board = access.board(boardCode)
        // 판이 있을 수 있는 게시판(승인 설정이 켜진)에서만 묻는다 - 기본 설정의 수정은 쿼리가 늘지 않는다. 꺼진 뒤 남은 판은 직접 수정이 저장소 트랜잭션에서 함께 지운다
        val existing = if (revisionsOn(board)) revisions.find(RevisionTarget.POST, listOf(post.id))[post.id] else null
        // 옛 키를 그대로 다시 보낸 수정은 통과 — 공개본에도 판에도 없던 새 키만 본다
        authorRules.rejectAccountIdKeys(caller, attachments.orEmpty().filterNot { it in post.attachments || (existing != null && it in existing.attachments) })
        if (!attachments.isNullOrEmpty() && !policy.canAttach(caller, attachments)) forbidden("Not allowed to attach these files")
        request.commentApproval?.let { requireOwnerModerates(board) }
        val content = request.title != null || request.body != null || attachments != null
        // 승인 대상 작성자가 공개된 글을 고치면 공개본은 그대로 두고 고친 내용만 판으로 대기한다
        if (content && post.status == PostStatus.PUBLISHED && policy.needsApproval(board, null, caller, ApprovalTarget.POST)) {
            if (request.status != null && request.status != PostStatus.PUBLISHED) invalid("a published post cannot change status while its edit waits for approval")
            request.commentApproval?.let { posts.update(post.id, PostChange(commentApproval = it), time.now()) }
            val rev = submitRevision(post, existing, request.title?.let { rules.title(it) }, request.body?.let { rules.postBody(it) }, attachments, actorOf(caller))
            return detail(caller, posts.find(post.id) ?: post, rev)
        }
        // 임시저장을 공개로 바꾸는 것은 새 글이 나오는 순간이라 승인 대상이면 대기로 간다
        // 대기 중인 글을 쓴 사람이 스스로 공개로 바꿀 수는 없다 — 대기 그대로 (공개는 운영자 승인)
        // 승인 설정을 끈 뒤에도 이미 대기 중인 글은 대기에 남는다(결정 14) - 스스로 공개할 수 없다
        val status = when {
            request.status == PostStatus.PUBLISHED && post.status == PostStatus.PENDING && !policy.canModerate(caller) -> PostStatus.PENDING
            request.status == PostStatus.PUBLISHED && post.status == PostStatus.DRAFT && policy.needsApproval(board, null, caller, ApprovalTarget.POST) -> PostStatus.PENDING
            else -> request.status
        }
        val change = PostChange(
            title = request.title?.let { rules.title(it) },
            body = request.body?.let { rules.postBody(it) },
            attachments = attachments,
            status = status,
            commentApproval = request.commentApproval,
        )
        val updated = posts.update(post.id, change, time.now()) ?: notFound(postId)
        events.publish(PostUpdated(updated, actorOf(caller)))
        if (updated.status != post.status) events.publish(PostStatusChanged(updated, post.status, updated.status, actorOf(caller)))
        // 승인 대상이 아닌 사람의 수정(운영자 포함)이 이긴다 — 대기 중이던 판은 저장소가 같은 트랜잭션에서 버렸고, 여기서는 알린다
        if (existing != null && (content || updated.status != PostStatus.PUBLISHED)) announceDiscard(post, actorOf(caller))
        return detail(caller, updated)
    }

    private fun submitRevision(post: Post, existing: Revision?, title: String?, body: String?, attachments: List<String>?, actor: BoardActor): Revision {
        val now = time.now()
        val rev = Revision(
            RevisionTarget.POST, post.id, post.boardCode, post.id,
            title ?: existing?.title ?: post.title, body ?: existing?.body ?: post.body, attachments ?: existing?.attachments ?: post.attachments,
            existing?.createdAt ?: now, now,
        )
        val result = revisions.submit(rev)
        // 잠근 뒤 보니 글이 이미 가려졌거나 지워졌다 - 판을 넣지 않는다. 더 이상 고칠 수 없는 글이라 없는 것과 같게 답한다
        if (result == RevisionSubmit.TARGET_GONE) throw BoardException(BoardErrorCode.POST_NOT_FOUND, "Post is no longer public: ${post.id}")
        events.publish(RevisionSubmitted(RevisionTarget.POST, post.id, post.boardCode, post.id, result == RevisionSubmit.REPLACED, actor))
        // 저장된 판(DB 가 매긴 시각 = 버전)을 돌려준다
        return revisions.find(RevisionTarget.POST, listOf(post.id))[post.id] ?: rev
    }

    private fun revisionsOn(board: Board) = board.settings.postApproval != ApprovalMode.OFF || board.settings.commentApproval != ApprovalMode.OFF

    /** 공개를 떠나는 글에 대기 중인 판이 있는가 — 저장소가 같은 트랜잭션에서 지우므로 바꾸기 **전에** 본다 */
    private fun hadRevisionBeingDropped(post: Post, newStatus: PostStatus?): Boolean =
        post.status == PostStatus.PUBLISHED && newStatus != null && newStatus != PostStatus.PUBLISHED &&
            revisions.find(RevisionTarget.POST, listOf(post.id)).isNotEmpty()

    private fun announceDiscard(post: Post, actor: BoardActor) =
        events.publish(RevisionDecided(RevisionTarget.POST, post.id, post.boardCode, post.id, RevisionOutcome.DISCARDED, actor))

    private fun updateAsGuest(boardCode: String, postId: Long, request: UpdatePost, guest: GuestContext?): PostDetailView {
        val ctx = guestWriter(access.board(boardCode), guest)
        guests.admit(ctx, checkCaptcha = false)
        val post = guestOwned(boardCode, postId, ctx)
        if (post.status !in GUEST_EDITABLE) forbidden(BoardGuests.FORBIDDEN_MESSAGE)
        if (request.status != null && request.status != post.status) invalid("guest posts cannot change status")
        val attachments = request.attachments?.let { rules.attachments(it) }
        val board = access.board(boardCode)
        val existing = if (revisionsOn(board)) revisions.find(RevisionTarget.POST, listOf(post.id))[post.id] else null
        if (!attachments.isNullOrEmpty() && !policy.canGuestAttach(attachments.filterNot { it in post.attachments || (existing != null && it in existing.attachments) })) forbidden("Not allowed to attach these files")
        val content = request.title != null || request.body != null || attachments != null
        if (content && post.status == PostStatus.PUBLISHED && policy.needsApproval(board, null, null, ApprovalTarget.POST)) {
            val rev = submitRevision(post, existing, request.title?.let { rules.title(it) }, request.body?.let { rules.postBody(it) }, attachments, GUEST)
            return detail(null, post, rev)   // 비회원은 다시 읽을 때 판을 보지 못한다 — 이 응답을 화면이 들고 있는다
        }
        val change = PostChange(
            title = request.title?.let { rules.title(it) },
            body = request.body?.let { rules.postBody(it) },
            attachments = attachments,
        )
        val updated = posts.update(post.id, change, time.now()) ?: notFound(postId)
        events.publish(PostUpdated(updated, GUEST))
        if (existing != null && content) announceDiscard(post, GUEST)
        return detail(null, updated)
    }

    fun delete(caller: BoardCaller?, boardCode: String, postId: Long, guest: GuestContext? = null) {
        if (caller == null) return deleteAsGuest(boardCode, postId, guest)
        val post = access.post(caller, boardCode, postId)
        if (!policy.canDeletePost(caller, post)) forbidden("Not allowed to delete post $postId")
        if (post.status != PostStatus.DELETED) {
            val dropped = hadRevisionBeingDropped(post, PostStatus.DELETED)
            posts.update(post.id, PostChange(status = PostStatus.DELETED), time.now())?.let {
                events.publish(PostStatusChanged(it, post.status, PostStatus.DELETED, actorOf(caller)))
                if (dropped) announceDiscard(it, actorOf(caller))
            }
        }
    }

    private fun deleteAsGuest(boardCode: String, postId: Long, guest: GuestContext?) {
        val ctx = guestWriter(access.board(boardCode), guest)
        guests.admit(ctx, checkCaptcha = false)
        val post = guestOwned(boardCode, postId, ctx)
        if (post.status != PostStatus.DELETED) {
            val dropped = hadRevisionBeingDropped(post, PostStatus.DELETED)
            posts.update(post.id, PostChange(status = PostStatus.DELETED), time.now())?.let {
                events.publish(PostStatusChanged(it, post.status, PostStatus.DELETED, GUEST))
                if (dropped) announceDiscard(it, GUEST)
            }
        }
    }

    fun moderate(caller: BoardCaller, boardCode: String, postId: Long, status: PostStatus?, pinned: Boolean?): PostDetailView {
        if (!policy.canModerate(caller)) forbidden("Only moderators moderate")
        val post = access.post(caller, boardCode, postId)
        if (status != null && status !in MODERATION_STATUSES) invalid("moderation status must be PUBLISHED, HIDDEN or DELETED")
        val dropped = hadRevisionBeingDropped(post, status)
        val updated = posts.update(post.id, PostChange(status = status, pinned = pinned, moderatedBy = if (status != null) ModeratedBy.MODERATOR else null), time.now()) ?: notFound(postId)
        if (updated.status != post.status) events.publish(PostStatusChanged(updated, post.status, updated.status, actorOf(caller)))
        if (dropped) announceDiscard(updated, actorOf(caller))
        if (updated.pinned != post.pinned) events.publish(PostUpdated(updated, actorOf(caller)))
        return detail(caller, updated)
    }

    private fun scope(caller: BoardCaller?, request: PostListRequest): Pair<String?, Set<PostStatus>> {
        if (request.mine) {
            val me = caller ?: forbidden("Sign in to list your posts")
            return me.accountId to (request.status?.let { setOf(it) } ?: setOf(PostStatus.DRAFT, PostStatus.PENDING, PostStatus.PUBLISHED, PostStatus.HIDDEN))
        }
        val status = request.status ?: PostStatus.PUBLISHED
        if (status != PostStatus.PUBLISHED && (caller == null || !policy.canModerate(caller))) forbidden("Only moderators list $status posts")
        return null to setOf(status)
    }

    private fun detail(caller: BoardCaller?, post: Post, revision: Revision? = null): PostDetailView {
        val publicId = BoardAuthors.publicId(post.authorRef, post.authorId, BoardAuthors.POST_ROW, post.id, authorRules.active)
        val card = if (BoardAuthors.isGuest(post.authorId)) AuthorCard(post.guestName) else authors.one(post.boardCode, publicId)
        val board = if (caller != null) access.board(post.boardCode) else null
        val manage = caller != null && board != null && policy.canManageComments(caller, post, board)
        return PostDetailView(
            post, reactions.state(ReactionTarget.POST, post.id, caller), publicId,
            BoardAuthors.isDeleted(post.authorId) || card?.deleted == true, caller?.accountId == post.authorId, card, manage,
            (revision ?: board?.let { visibleRevision(caller, post, it) })?.view(),
        )
    }

    /** 쓴 회원 · 운영자에게만 판을 보인다 (남에게는 null — 판이 있다는 표시도 없다) */
    private fun visibleRevision(caller: BoardCaller?, post: Post, board: Board): Revision? =
        if (revisionsOn(board) && caller != null && (caller.moderator || caller.accountId == post.authorId)) revisions.find(RevisionTarget.POST, listOf(post.id))[post.id] else null

    /** 글마다 댓글 승인 규칙을 고르는 것은 글 주인이 댓글을 관리하는 게시판에서만 */
    private fun requireOwnerModerates(board: Board) {
        if (!board.settings.ownerModeratesComments) invalid("commentApproval needs a board where owners moderate their comments")
    }

    private fun rateLimit(caller: BoardCaller) {
        if (!limiter.tryAcquire(caller.accountId, "post")) throw BoardException(BoardErrorCode.RATE_LIMITED, "Too many posts, slow down")
    }

    /** 비회원 쓰기를 받을 수 있는 요청인가 — 아니면 지금과 같은 401 */
    private fun guestWriter(board: Board, guest: GuestContext?): GuestContext {
        if (guest == null || !properties.guest.enabled || board.settings.guestWrite == GuestWrite.NONE) throw guests.unauthorized()
        return guest
    }

    /**
     * 비밀이 맞는 비회원 글. 틀린 비밀 · 회원 글 · 헤더 없음은 같은 403 이고, **공개 전인 글**(승인 대기 · 가림 …)이면 존재를 알리지 않으려고 404 다.
     * 대입 한도는 [BoardGuests.verify] 가 해시 전에 센다.
     */
    private fun guestOwned(boardCode: String, postId: Long, ctx: GuestContext): Post {
        val post = posts.find(postId)?.takeIf { it.boardCode == boardCode } ?: notFound(postId)
        if (guests.verify(ctx, "p${post.id}", if (BoardAuthors.isGuest(post.authorId)) posts.guestSecretHash(post.id) else null)) return post
        if (policy.canViewPost(null, post)) forbidden(BoardGuests.FORBIDDEN_MESSAGE) else notFound(postId)
    }

    private fun actorOf(caller: BoardCaller) = BoardActor(if (caller.moderator) BoardActorKind.MODERATOR else BoardActorKind.MEMBER, caller.accountId)

    private fun forbidden(message: String): Nothing = throw BoardException(BoardErrorCode.FORBIDDEN, message)

    private fun invalid(message: String): Nothing = throw BoardException(BoardErrorCode.CONTENT_INVALID, message)

    private fun notFound(postId: Long): Nothing = throw BoardException(BoardErrorCode.POST_NOT_FOUND, "Post not found: $postId")

    private companion object {
        val WRITABLE_STATUSES = setOf(PostStatus.DRAFT, PostStatus.PUBLISHED)
        val MODERATION_STATUSES = setOf(PostStatus.PUBLISHED, PostStatus.HIDDEN, PostStatus.DELETED)
        val GUEST_EDITABLE = setOf(PostStatus.PENDING, PostStatus.PUBLISHED)
        val GUEST = BoardActor(BoardActorKind.GUEST)
    }
}
