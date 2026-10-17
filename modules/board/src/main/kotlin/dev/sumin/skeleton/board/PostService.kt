package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.time.TimeProvider

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
        val states = reactions.states(ReactionTarget.POST, page.values.map { it.id }, caller)
        val active = authorRules.active
        val publicIds = page.values.associate { it.id to BoardAuthors.publicId(it.authorRef, it.authorId, BoardAuthors.POST_ROW, it.id, active) }
        val names = authors.of(boardCode, publicIds.values)
        return PageResult(
            page.values.map {
                val publicId = publicIds.getValue(it.id)
                val card = if (BoardAuthors.isGuest(it.authorId)) AuthorCard(it.guestName) else names[publicId]
                PostSummaryView(it, states.getValue(it.id), publicId, BoardAuthors.isDeleted(it.authorId) || card?.deleted == true, caller?.accountId == it.authorId, card)
            },
            page.totalElements,
        )
    }

    fun update(caller: BoardCaller?, boardCode: String, postId: Long, request: UpdatePost, guest: GuestContext? = null): PostDetailView {
        if (caller == null) return updateAsGuest(boardCode, postId, request, guest)
        val post = access.post(caller, boardCode, postId)
        if (!policy.canEditPost(caller, post)) forbidden("Not allowed to edit post $postId")
        request.status?.let { if (it !in WRITABLE_STATUSES) invalid("status must be DRAFT or PUBLISHED (moderators use /moderation)") }
        val attachments = request.attachments?.let { rules.attachments(it) }
        authorRules.rejectAccountIdKeys(caller, attachments.orEmpty().filterNot { it in post.attachments })   // 옛 키를 그대로 다시 보낸 수정은 통과 — 새로 붙는 키만 본다
        if (!attachments.isNullOrEmpty() && !policy.canAttach(caller, attachments)) forbidden("Not allowed to attach these files")
        request.commentApproval?.let { requireOwnerModerates(access.board(boardCode)) }
        // 임시저장을 공개로 바꾸는 것은 새 글이 나오는 순간이라 승인 대상이면 대기로 간다
        val status = if (request.status == PostStatus.PUBLISHED && post.status == PostStatus.DRAFT &&
            policy.needsApproval(access.board(boardCode), null, caller, ApprovalTarget.POST)
        ) PostStatus.PENDING else request.status
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
        return detail(caller, updated)
    }

    private fun updateAsGuest(boardCode: String, postId: Long, request: UpdatePost, guest: GuestContext?): PostDetailView {
        val ctx = guestWriter(access.board(boardCode), guest)
        guests.admit(ctx, checkCaptcha = false)
        val post = guestOwned(boardCode, postId, ctx)
        if (post.status !in GUEST_EDITABLE) forbidden(BoardGuests.FORBIDDEN_MESSAGE)
        if (request.status != null && request.status != post.status) invalid("guest posts cannot change status")
        val attachments = request.attachments?.let { rules.attachments(it) }
        if (!attachments.isNullOrEmpty() && !policy.canGuestAttach(attachments.filterNot { it in post.attachments })) forbidden("Not allowed to attach these files")
        val change = PostChange(
            title = request.title?.let { rules.title(it) },
            body = request.body?.let { rules.postBody(it) },
            attachments = attachments,
        )
        val updated = posts.update(post.id, change, time.now()) ?: notFound(postId)
        events.publish(PostUpdated(updated, GUEST))
        return detail(null, updated)
    }

    fun delete(caller: BoardCaller?, boardCode: String, postId: Long, guest: GuestContext? = null) {
        if (caller == null) return deleteAsGuest(boardCode, postId, guest)
        val post = access.post(caller, boardCode, postId)
        if (!policy.canDeletePost(caller, post)) forbidden("Not allowed to delete post $postId")
        if (post.status != PostStatus.DELETED) {
            posts.update(post.id, PostChange(status = PostStatus.DELETED), time.now())?.let {
                events.publish(PostStatusChanged(it, post.status, PostStatus.DELETED, actorOf(caller)))
            }
        }
    }

    private fun deleteAsGuest(boardCode: String, postId: Long, guest: GuestContext?) {
        val ctx = guestWriter(access.board(boardCode), guest)
        guests.admit(ctx, checkCaptcha = false)
        val post = guestOwned(boardCode, postId, ctx)
        if (post.status != PostStatus.DELETED) {
            posts.update(post.id, PostChange(status = PostStatus.DELETED), time.now())?.let {
                events.publish(PostStatusChanged(it, post.status, PostStatus.DELETED, GUEST))
            }
        }
    }

    fun moderate(caller: BoardCaller, boardCode: String, postId: Long, status: PostStatus?, pinned: Boolean?): PostDetailView {
        if (!policy.canModerate(caller)) forbidden("Only moderators moderate")
        val post = access.post(caller, boardCode, postId)
        if (status != null && status !in MODERATION_STATUSES) invalid("moderation status must be PUBLISHED, HIDDEN or DELETED")
        val updated = posts.update(post.id, PostChange(status = status, pinned = pinned, moderatedBy = if (status != null) ModeratedBy.MODERATOR else null), time.now()) ?: notFound(postId)
        if (updated.status != post.status) events.publish(PostStatusChanged(updated, post.status, updated.status, actorOf(caller)))
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

    private fun detail(caller: BoardCaller?, post: Post): PostDetailView {
        val publicId = BoardAuthors.publicId(post.authorRef, post.authorId, BoardAuthors.POST_ROW, post.id, authorRules.active)
        val card = if (BoardAuthors.isGuest(post.authorId)) AuthorCard(post.guestName) else authors.one(post.boardCode, publicId)
        val manage = caller != null && policy.canManageComments(caller, post, access.board(post.boardCode))
        return PostDetailView(
            post, reactions.state(ReactionTarget.POST, post.id, caller), publicId,
            BoardAuthors.isDeleted(post.authorId) || card?.deleted == true, caller?.accountId == post.authorId, card, manage,
        )
    }

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
