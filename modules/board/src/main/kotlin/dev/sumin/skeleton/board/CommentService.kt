package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.time.TimeProvider
import org.slf4j.LoggerFactory

/**
 * 댓글 트리. 저장은 parent_id + root_id + depth — 최상위 댓글만 페이지로 나누고 그 모든 자손은 root_id 로 쿼리 한 번에 가져온다 (N+1 없음).
 * 지워도 행은 남아 스레드 모양이 유지되고, 본문만 모두에게 숨긴다.
 */
class CommentService(
    private val access: BoardAccess,
    private val comments: CommentRepository,
    private val reactions: ReactionSupport,
    private val authors: AuthorNames,
    private val policy: BoardPolicy,
    private val rules: BoardContentRules,
    private val properties: BoardProperties,
    private val limiter: BoardRateLimiter,
    private val notifier: BoardNotifier,
    private val time: TimeProvider,
    private val authorRules: BoardAuthorRules,
    private val events: BoardEvents = BoardEvents.NONE,
    private val guests: BoardGuests = BoardGuests(properties),
) {
    private val log = LoggerFactory.getLogger(CommentService::class.java)

    /** [caller] 가 null 이면 로그인하지 않은 쓰기 — [guest] 가 있고 게시판이 허락하면 비회원 댓글, 아니면 401 */
    fun create(
        caller: BoardCaller?, boardCode: String, postId: Long, parentId: Long?, body: String?, authorId: String? = null,
        guestName: String? = null, guest: GuestContext? = null,
    ): CommentView {
        val board = access.board(boardCode)
        val post = access.post(caller, boardCode, postId)
        val ctx = if (caller == null) guestWriter(board, guest) else null
        if (caller != null) {
            if (guestName != null) throw BoardException(BoardErrorCode.CONTENT_INVALID, "guestName is for signed-out writers only")
            if (!policy.canComment(caller, post)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to comment")
        } else if (!policy.canGuestComment(board, post)) {
            throw BoardException(BoardErrorCode.FORBIDDEN, "Guests cannot comment here")
        }
        if (post.status != PostStatus.PUBLISHED) throw BoardException(BoardErrorCode.POST_NOT_COMMENTABLE, "Post $postId does not take comments")
        val authorRef = if (caller != null) authorRules.authorRefFor(caller, authorId, boardCode) else {
            if (authorId != null) throw BoardException(BoardErrorCode.AUTHOR_NOT_ALLOWED, "Not allowed to write as this author")
            null
        }
        val parent = parentId?.let { access.comment(post, it) }
        if (parent != null && parent.status != CommentStatus.PUBLISHED) {
            throw BoardException(BoardErrorCode.POST_NOT_COMMENTABLE, "Comment ${parent.id} does not take replies")
        }
        val depth = (parent?.depth ?: -1) + 1
        if (depth > properties.maxCommentDepth) {
            throw BoardException(BoardErrorCode.COMMENT_TOO_DEEP, "Replies can be nested ${properties.maxCommentDepth} levels below a top-level comment")
        }
        val text = rules.commentBody(body)
        val status = if (policy.needsApproval(board, post, caller, ApprovalTarget.COMMENT)) CommentStatus.PENDING else CommentStatus.PUBLISHED
        val new = if (ctx == null) {
            if (!limiter.tryAcquire(caller!!.accountId, "comment")) throw BoardException(BoardErrorCode.RATE_LIMITED, "Too many comments, slow down")
            NewComment(post.id, parent?.id, parent?.rootId, depth, caller.accountId, text, time.now(), authorRef, status)
        } else {
            val name = guests.name(guestName, rules)
            val secret = guests.validateNewSecret(ctx)
            guests.admit(ctx, checkCaptcha = true)
            NewComment(post.id, parent?.id, parent?.rootId, depth, guests.newAuthorId(), text, time.now(), null, status, guestName = name, guestSecretHash = guests.hash(secret))
        }
        val created = comments.insert(new) ?: throw BoardException(BoardErrorCode.POST_NOT_FOUND, "Post not found: $postId")
        events.publish(CommentCreated(created, boardCode, if (caller != null) actorOf(caller) else GUEST))
        val publicId = publicId(created, authorRules.active)
        val author = card(created, authors.one(boardCode, publicId))   // 응답과 알림이 한 번의 조회를 같이 쓴다
        if (created.status == CommentStatus.PUBLISHED) notify(post, created, parent, publicId, author?.name) else notifyPending(board, post, created, parent, publicId, author?.name)
        return view(caller, created, publicId, author, created.body, reactions.state(ReactionTarget.COMMENT, created.id, caller), 0)
    }

    fun list(caller: BoardCaller?, boardCode: String, postId: Long, page: Int, size: Int, sort: CommentSort): PageResult<CommentView> {
        val post = access.post(caller, boardCode, postId)
        // 승인 대기 댓글은 운영자 · 관리하는 글 주인 · 쓴 회원에게만 — 그 밖의 사람에게는 스레드에 자리도 없고 replyCount 에도 들어가지 않는다
        val pending = pendingVisibility(caller, access.board(boardCode), post)
        val roots = comments.rootPage(CommentRootQuery(post.id, page, properties.pageSize(size), sort, pending))
        if (roots.values.isEmpty()) return PageResult(emptyList(), roots.totalElements)
        val descendants = comments.descendants(roots.values.map { it.id }).filter { pending.sees(it) }
        val states = reactions.states(ReactionTarget.COMMENT, (roots.values + descendants).map { it.id }, caller)
        val active = authorRules.active
        val publicIds = (roots.values + descendants).associate { it.id to publicId(it, active) }
        val names = authors.of(boardCode, publicIds.values)
        val childrenOf = descendants.groupBy { it.parentId }
        val repliesOf = descendants.groupBy { it.rootId }
        fun view(c: Comment, replies: List<CommentView>) =
            view(caller, c, publicIds.getValue(c.id), card(c, names[publicIds.getValue(c.id)]), visibleBody(c), states.getValue(c.id), descendantCount(c.id, childrenOf), replies)
        val threads = roots.values.map { root ->
            view(root, repliesOf[root.id].orEmpty().map { view(it, emptyList()) })
        }
        return PageResult(threads, roots.totalElements)
    }

    fun update(caller: BoardCaller?, boardCode: String, postId: Long, commentId: Long, body: String?, guest: GuestContext? = null): CommentView {
        val post = access.post(caller, boardCode, postId)
        if (caller == null) return updateAsGuest(boardCode, post, commentId, body, guest)
        val comment = access.comment(post, commentId)
        if (!policy.canEditComment(caller, comment)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to edit comment $commentId")
        val updated = comments.updateBody(comment.id, rules.commentBody(body), time.now()) ?: notFound(commentId)
        events.publish(CommentUpdated(updated, boardCode, actorOf(caller)))
        return single(caller, boardCode, updated, pendingVisibility(caller, access.board(boardCode), post))
    }

    private fun updateAsGuest(boardCode: String, post: Post, commentId: Long, body: String?, guest: GuestContext?): CommentView {
        val ctx = guestWriter(access.board(boardCode), guest)
        guests.admit(ctx, checkCaptcha = false)
        val comment = guestOwned(post, commentId, ctx)
        if (comment.status !in GUEST_EDITABLE) throw BoardException(BoardErrorCode.FORBIDDEN, BoardGuests.FORBIDDEN_MESSAGE)
        val updated = comments.updateBody(comment.id, rules.commentBody(body), time.now()) ?: notFound(commentId)
        events.publish(CommentUpdated(updated, boardCode, GUEST))
        return single(null, boardCode, updated, PendingVisibility.NONE)
    }

    fun delete(caller: BoardCaller?, boardCode: String, postId: Long, commentId: Long, guest: GuestContext? = null) {
        val post = access.post(caller, boardCode, postId)
        if (caller == null) return deleteAsGuest(boardCode, post, commentId, guest)
        val comment = access.comment(post, commentId)
        if (!policy.canDeleteComment(caller, comment)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to delete comment $commentId")
        if (comment.status != CommentStatus.DELETED) {
            comments.setStatus(comment.id, CommentStatus.DELETED, time.now())?.let {
                events.publish(CommentStatusChanged(it, boardCode, comment.status, CommentStatus.DELETED, actorOf(caller)))
            }
        }
    }

    private fun deleteAsGuest(boardCode: String, post: Post, commentId: Long, guest: GuestContext?) {
        val ctx = guestWriter(access.board(boardCode), guest)
        guests.admit(ctx, checkCaptcha = false)
        val comment = guestOwned(post, commentId, ctx)
        if (comment.status != CommentStatus.DELETED) {
            comments.setStatus(comment.id, CommentStatus.DELETED, time.now())?.let {
                events.publish(CommentStatusChanged(it, boardCode, comment.status, CommentStatus.DELETED, GUEST))
            }
        }
    }

    /**
     * 댓글 상태를 바꾼다 — 숨김 · 복구 · 삭제(운영자), 승인(PENDING → PUBLISHED) · 숨김(글 주인). 글 주인은 [GuestContext] 의 비밀로 증명하는 비회원 글 주인일 수도 있다.
     * 글 주인은 PUBLISHED · HIDDEN 만 고를 수 있고, **운영자가 가린 댓글은 되살리지 못한다**.
     */
    fun moderate(caller: BoardCaller?, boardCode: String, postId: Long, commentId: Long, status: CommentStatus, guest: GuestContext? = null): CommentView {
        val board = access.board(boardCode)
        val post = access.post(caller, boardCode, postId)
        val by = manager(caller, board, post, guest)
        if (status == CommentStatus.PENDING) throw BoardException(BoardErrorCode.CONTENT_INVALID, "moderation status must be PUBLISHED, HIDDEN or DELETED")
        val comment = access.comment(post, commentId)
        if (by == ModeratedBy.OWNER) {
            val blocked = status == CommentStatus.DELETED || comment.status == CommentStatus.DELETED ||
                (status == CommentStatus.PUBLISHED && comment.status == CommentStatus.HIDDEN && comment.moderatedBy == ModeratedBy.MODERATOR)
            if (blocked) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to moderate comment $commentId")
        }
        val updated = comments.setStatus(comment.id, status, time.now(), by) ?: notFound(commentId)
        if (updated.status != comment.status) {
            events.publish(CommentStatusChanged(updated, boardCode, comment.status, updated.status, BoardActor(if (by == ModeratedBy.OWNER) BoardActorKind.OWNER else BoardActorKind.MODERATOR, caller?.accountId)))
            if (comment.status == CommentStatus.PENDING && updated.status == CommentStatus.PUBLISHED) notifyApproved(post, updated)
        }
        return single(caller, boardCode, updated, pendingVisibility(caller, board, post))
    }

    /** 승인 대기 댓글 목록(오래된 순, 평평하게) — 댓글을 관리하는 사람(운영자 · 글 주인)에게만 */
    fun listPending(caller: BoardCaller?, boardCode: String, postId: Long, page: Int, size: Int, guest: GuestContext? = null): PageResult<CommentView> {
        val board = access.board(boardCode)
        val post = access.post(caller, boardCode, postId)
        manager(caller, board, post, guest)
        val rows = comments.pendingPage(post.id, page, properties.pageSize(size))
        if (rows.values.isEmpty()) return PageResult(emptyList(), rows.totalElements)
        val states = reactions.states(ReactionTarget.COMMENT, rows.values.map { it.id }, caller)
        val active = authorRules.active
        val publicIds = rows.values.associate { it.id to publicId(it, active) }
        val names = authors.of(boardCode, publicIds.values)
        return PageResult(
            rows.values.map { view(caller, it, publicIds.getValue(it.id), card(it, names[publicIds.getValue(it.id)]), it.body, states.getValue(it.id), 0) },
            rows.totalElements,
        )
    }

    /**
     * 이 글의 댓글을 관리하는 쪽: 운영자 → [ModeratedBy.MODERATOR], 댓글 관리가 켜진 게시판의 글 주인 → [ModeratedBy.OWNER].
     * 비회원 글 주인은 비밀이 글의 것과 맞아야 한다 (대입 한도는 해시 전에). 아니면 403 — 로그인하지 않은 쪽이 비회원도 못 쓰는 요청이면 401.
     */
    private fun manager(caller: BoardCaller?, board: Board, post: Post, guest: GuestContext?): ModeratedBy {
        if (caller != null) {
            if (policy.canModerate(caller)) return ModeratedBy.MODERATOR
            if (policy.canManageComments(caller, post, board)) return ModeratedBy.OWNER
            throw BoardException(BoardErrorCode.FORBIDDEN, "Only moderators and managing post owners moderate")
        }
        val ctx = guestWriter(board, guest)
        guests.admit(ctx, checkCaptcha = false)
        if (board.settings.ownerModeratesComments && guests.verify(ctx, "p${post.id}", access.guestSecretHash(post))) return ModeratedBy.OWNER
        throw BoardException(BoardErrorCode.FORBIDDEN, BoardGuests.FORBIDDEN_MESSAGE)
    }

    /** 승인 대기 댓글을 누구에게 보이나 — 관리하는 사람 전부, 회원 작성자는 자기 것 */
    private fun pendingVisibility(caller: BoardCaller?, board: Board, post: Post) =
        PendingVisibility(all = caller != null && policy.canManageComments(caller, post, board), authorId = caller?.accountId)

    private fun actorOf(caller: BoardCaller) = BoardActor(if (caller.moderator) BoardActorKind.MODERATOR else BoardActorKind.MEMBER, caller.accountId)

    /** 비회원 쓰기를 받을 수 있는 요청인가 — 아니면 지금과 같은 401 */
    private fun guestWriter(board: Board, guest: GuestContext?): GuestContext {
        if (guest == null || !properties.guest.enabled || board.settings.guestWrite == GuestWrite.NONE) throw guests.unauthorized()
        return guest
    }

    /** 비밀이 맞는 비회원 댓글. 틀린 비밀 · 회원 댓글 · 헤더 없음은 같은 403, 공개 전(승인 대기)이면 존재를 알리지 않으려고 404 */
    private fun guestOwned(post: Post, commentId: Long, ctx: GuestContext): Comment {
        val comment = access.comment(post, commentId)
        if (guests.verify(ctx, "c${comment.id}", if (BoardAuthors.isGuest(comment.authorId)) comments.guestSecretHash(comment.id) else null)) return comment
        if (comment.status == CommentStatus.PENDING) notFound(commentId)
        throw BoardException(BoardErrorCode.FORBIDDEN, BoardGuests.FORBIDDEN_MESSAGE)
    }

    private fun card(c: Comment, fromDirectory: AuthorCard?): AuthorCard? = if (BoardAuthors.isGuest(c.authorId)) AuthorCard(c.guestName) else fromDirectory

    private fun single(caller: BoardCaller?, boardCode: String, comment: Comment, pending: PendingVisibility): CommentView {
        val childrenOf = comments.descendants(listOf(comment.rootId)).filter { pending.sees(it) }.groupBy { it.parentId }
        val publicId = publicId(comment, authorRules.active)
        return view(
            caller, comment, publicId, card(comment, authors.one(boardCode, publicId)), visibleBody(comment),
            reactions.state(ReactionTarget.COMMENT, comment.id, caller), descendantCount(comment.id, childrenOf),
        )
    }

    private fun publicId(c: Comment, active: Boolean) = BoardAuthors.publicId(c.authorRef, c.authorId, BoardAuthors.COMMENT_ROW, c.id, active)

    private fun view(
        caller: BoardCaller?, c: Comment, publicId: String, card: AuthorCard?, body: String?,
        state: ReactionState, replyCount: Int, replies: List<CommentView> = emptyList(),
    ) = CommentView(c, publicId, BoardAuthors.isDeleted(c.authorId) || card?.deleted == true, caller?.accountId == c.authorId, body, state, replyCount, replies, card)

    /** PUBLISHED 와 (보이는 사람에게만 남아 있는) PENDING 의 본문. 가림 · 삭제는 모두에게 null */
    private fun visibleBody(c: Comment): String? = c.body.takeIf { c.status == CommentStatus.PUBLISHED || c.status == CommentStatus.PENDING }

    private fun descendantCount(id: Long, childrenOf: Map<Long?, List<Comment>>): Int =
        childrenOf[id].orEmpty().sumOf { 1 + descendantCount(it.id, childrenOf) }

    /** 답글이면 부모 댓글의 작성자에게, 최상위 댓글이면 글 작성자에게 — 자기 자신에게는 보내지 않는다. 알림 실패는 댓글 작성을 막지 않는다. */
    private fun notify(post: Post, comment: Comment, parent: Comment?, publicAuthorId: String, authorName: String?) {
        val recipient = parent?.authorId ?: post.authorId
        if (recipient == comment.authorId || BoardAuthors.isGuest(recipient)) return
        try {
            notifier.commentCreated(BoardCommentNotice(recipient, post, comment, parent, publicAuthorId, authorName))
        } catch (e: Exception) {
            log.warn("board comment notification failed (comment {}): {}", comment.id, e.toString())
        }
    }

    /** 승인 대기로 들어온 댓글 — 댓글을 관리하는 글 주인(회원)에게만 `comment.pending` */
    private fun notifyPending(board: Board, post: Post, comment: Comment, parent: Comment?, publicAuthorId: String, authorName: String?) {
        val recipient = post.authorId
        if (!board.settings.ownerModeratesComments || recipient == comment.authorId || BoardAuthors.isGuest(recipient)) return
        try {
            notifier.commentPending(BoardCommentNotice(recipient, post, comment, parent, publicAuthorId, authorName))
        } catch (e: Exception) {
            log.warn("board pending-comment notification failed (comment {}): {}", comment.id, e.toString())
        }
    }

    /** 승인되어 공개된 순간 — 바로 공개되었을 때와 같은 알림이 한 번 */
    private fun notifyApproved(post: Post, comment: Comment) {
        val parent = comment.parentId?.let { comments.find(it) }
        val publicId = publicId(comment, authorRules.active)
        notify(post, comment, parent, publicId, card(comment, authors.one(post.boardCode, publicId))?.name)
    }

    private companion object {
        val GUEST_EDITABLE = setOf(CommentStatus.PENDING, CommentStatus.PUBLISHED)
        val GUEST = BoardActor(BoardActorKind.GUEST)
    }

    private fun notFound(id: Long): Nothing = throw BoardException(BoardErrorCode.COMMENT_NOT_FOUND, "Comment not found: $id")
}
