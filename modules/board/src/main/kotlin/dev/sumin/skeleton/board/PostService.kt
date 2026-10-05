package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.time.TimeProvider

class PostService(
    private val access: BoardAccess,
    private val posts: PostRepository,
    private val reactions: ReactionSupport,
    private val policy: BoardPolicy,
    private val rules: BoardContentRules,
    private val properties: BoardProperties,
    private val limiter: BoardRateLimiter,
    private val time: TimeProvider,
) {
    fun create(caller: BoardCaller, boardCode: String, request: CreatePost): PostDetailView {
        val board = access.board(boardCode)
        if (!policy.canCreatePost(caller, board)) forbidden("Not allowed to post in $boardCode")
        val status = request.status ?: PostStatus.PUBLISHED
        if (status !in WRITABLE_STATUSES) invalid("status must be DRAFT or PUBLISHED")
        val title = rules.title(request.title)
        val body = rules.postBody(request.body)
        val attachments = rules.attachments(request.attachments)
        if (attachments.isNotEmpty() && !policy.canAttach(caller, attachments)) forbidden("Not allowed to attach these files")
        rateLimit(caller)
        val post = posts.insert(NewPost(boardCode, caller.accountId, title, body, status, attachments, time.now()))
        return detail(caller, post)
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
        return PageResult(page.values.map { PostSummaryView(it, states.getValue(it.id)) }, page.totalElements)
    }

    fun update(caller: BoardCaller, boardCode: String, postId: Long, request: UpdatePost): PostDetailView {
        val post = access.post(caller, boardCode, postId)
        if (!policy.canEditPost(caller, post)) forbidden("Not allowed to edit post $postId")
        request.status?.let { if (it !in WRITABLE_STATUSES) invalid("status must be DRAFT or PUBLISHED (moderators use /moderation)") }
        val attachments = request.attachments?.let { rules.attachments(it) }
        if (!attachments.isNullOrEmpty() && !policy.canAttach(caller, attachments)) forbidden("Not allowed to attach these files")
        val change = PostChange(
            title = request.title?.let { rules.title(it) },
            body = request.body?.let { rules.postBody(it) },
            attachments = attachments,
            status = request.status,
        )
        val updated = posts.update(post.id, change, time.now()) ?: notFound(postId)
        return detail(caller, updated)
    }

    fun delete(caller: BoardCaller, boardCode: String, postId: Long) {
        val post = access.post(caller, boardCode, postId)
        if (!policy.canDeletePost(caller, post)) forbidden("Not allowed to delete post $postId")
        if (post.status != PostStatus.DELETED) posts.update(post.id, PostChange(status = PostStatus.DELETED), time.now())
    }

    fun moderate(caller: BoardCaller, boardCode: String, postId: Long, status: PostStatus?, pinned: Boolean?): PostDetailView {
        if (!policy.canModerate(caller)) forbidden("Only moderators moderate")
        val post = access.post(caller, boardCode, postId)
        if (status != null && status !in MODERATION_STATUSES) invalid("moderation status must be PUBLISHED, HIDDEN or DELETED")
        val updated = posts.update(post.id, PostChange(status = status, pinned = pinned), time.now()) ?: notFound(postId)
        return detail(caller, updated)
    }

    private fun scope(caller: BoardCaller?, request: PostListRequest): Pair<String?, Set<PostStatus>> {
        if (request.mine) {
            val me = caller ?: forbidden("Sign in to list your posts")
            return me.accountId to (request.status?.let { setOf(it) } ?: setOf(PostStatus.DRAFT, PostStatus.PUBLISHED, PostStatus.HIDDEN))
        }
        val status = request.status ?: PostStatus.PUBLISHED
        if (status != PostStatus.PUBLISHED && (caller == null || !policy.canModerate(caller))) forbidden("Only moderators list $status posts")
        return null to setOf(status)
    }

    private fun detail(caller: BoardCaller?, post: Post) = PostDetailView(post, reactions.state(ReactionTarget.POST, post.id, caller))

    private fun rateLimit(caller: BoardCaller) {
        if (!limiter.tryAcquire(caller.accountId, "post")) throw BoardException(BoardErrorCode.RATE_LIMITED, "Too many posts, slow down")
    }

    private fun forbidden(message: String): Nothing = throw BoardException(BoardErrorCode.FORBIDDEN, message)

    private fun invalid(message: String): Nothing = throw BoardException(BoardErrorCode.CONTENT_INVALID, message)

    private fun notFound(postId: Long): Nothing = throw BoardException(BoardErrorCode.POST_NOT_FOUND, "Post not found: $postId")

    private companion object {
        val WRITABLE_STATUSES = setOf(PostStatus.DRAFT, PostStatus.PUBLISHED)
        val MODERATION_STATUSES = setOf(PostStatus.PUBLISHED, PostStatus.HIDDEN, PostStatus.DELETED)
    }
}
