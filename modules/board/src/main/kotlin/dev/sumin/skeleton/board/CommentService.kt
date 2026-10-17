package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
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
    private val revisions: BoardRevisionRepository = NoBoardRevisions,
    private val uploads: BoardUploads = BoardUploads.NONE,
) {
    private val log = LoggerFactory.getLogger(CommentService::class.java)

    /** [caller] 가 null 이면 로그인하지 않은 쓰기 — [guest] 가 있고 게시판이 허락하면 비회원 댓글, 아니면 401 */
    fun create(
        caller: BoardCaller?, boardCode: String, postId: Long, parentId: Long?, body: String?, authorId: String? = null,
        guestName: String? = null, guest: GuestContext? = null, attachments: List<String>? = null,
    ): CommentView {
        val board = access.board(boardCode)
        val ctx = if (caller == null) guestWriter(board, guest) else null   // 글을 찾기 전에 — 비회원이 못 쓰는 게시판은 지금과 같은 401
        val post = access.post(caller, boardCode, postId)
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
        if (parent != null && !pendingVisibility(caller, board, post).sees(parent)) throw BoardException(BoardErrorCode.COMMENT_NOT_FOUND, "Comment not found: ${parent.id}")   // 못 보는 대기 · 거절 댓글은 없는 것과 같다
        if (parent != null && parent.status != CommentStatus.PUBLISHED) {
            throw BoardException(BoardErrorCode.POST_NOT_COMMENTABLE, "Comment ${parent.id} does not take replies")
        }
        val depth = (parent?.depth ?: -1) + 1
        if (depth > properties.maxCommentDepth) {
            throw BoardException(BoardErrorCode.COMMENT_TOO_DEEP, "Replies can be nested ${properties.maxCommentDepth} levels below a top-level comment")
        }
        val text = rules.commentBody(body)
        val keys = rules.commentAttachments(attachments)
        if (caller != null) {
            authorRules.rejectAccountIdKeys(caller, keys)
            if (keys.isNotEmpty() && !policy.canAttach(caller, keys)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to attach these files")
        } else if (keys.isNotEmpty() && !policy.canGuestAttach(keys)) {
            throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to attach these files")
        }
        val status = if (policy.needsApproval(board, post, caller, ApprovalTarget.COMMENT)) CommentStatus.PENDING else CommentStatus.PUBLISHED
        val new = if (ctx == null) {
            if (!limiter.tryAcquire(caller!!.accountId, "comment")) throw BoardException(BoardErrorCode.RATE_LIMITED, "Too many comments, slow down")
            NewComment(post.id, parent?.id, parent?.rootId, depth, caller.accountId, text, time.now(), authorRef, status, attachments = keys)
        } else {
            val name = guests.name(guestName, rules)
            val secret = guests.validateNewSecret(ctx)
            guests.admit(ctx, checkCaptcha = true)
            NewComment(post.id, parent?.id, parent?.rootId, depth, guests.newAuthorId(), text, time.now(), null, status, guestName = name, guestSecretHash = guests.hash(secret), attachments = keys)
        }
        val created = comments.insert(new) ?: throw BoardException(BoardErrorCode.POST_NOT_FOUND, "Post not found: $postId")
        events.publish(CommentCreated(created, boardCode, if (caller != null) actorOf(caller) else GUEST))
        val publicId = publicId(created, authorRules.active)
        val author = card(created, authors.one(boardCode, publicId))   // 응답과 알림이 한 번의 조회를 같이 쓴다
        if (created.status == CommentStatus.PUBLISHED) notify(post, created, parent, publicId, author?.name) else notifyPending(board, post, created, parent, publicId, author?.name)
        return view(caller, created, publicId, author, created.body, reactions.state(ReactionTarget.COMMENT, created.id, caller), 0, attachments = keys, postPublic = post.status == PostStatus.PUBLISHED)
    }

    fun list(caller: BoardCaller?, boardCode: String, postId: Long, page: Int, size: Int, sort: CommentSort): PageResult<CommentView> {
        val post = access.post(caller, boardCode, postId)
        // 승인 대기 댓글은 운영자 · 관리하는 글 주인 · 쓴 회원에게만 — 그 밖의 사람에게는 스레드에 자리도 없고 replyCount 에도 들어가지 않는다
        val board = access.board(boardCode)
        val pending = pendingVisibility(caller, board, post)
        val roots = comments.rootPage(CommentRootQuery(post.id, page, properties.pageSize(size), sort, pending))
        if (roots.values.isEmpty()) return PageResult(emptyList(), roots.totalElements)
        val descendants = comments.descendants(roots.values.map { it.id }).filter { pending.sees(it) }
        val states = reactions.states(ReactionTarget.COMMENT, (roots.values + descendants).map { it.id }, caller)
        val active = authorRules.active
        val publicIds = (roots.values + descendants).associate { it.id to publicId(it, active) }
        val names = authors.of(boardCode, publicIds.values)
        val childrenOf = descendants.groupBy { it.parentId }
        val repliesOf = descendants.groupBy { it.rootId }
        val revs = if (caller == null || !revisionsOn(board, post)) emptyMap() else revisions.find(RevisionTarget.COMMENT, (roots.values + descendants).map { it.id })
        val files = attachmentsOf((roots.values + descendants).map { it.id })
        fun view(c: Comment, replies: List<CommentView>) =
            view(
                caller, c, publicIds.getValue(c.id), card(c, names[publicIds.getValue(c.id)]), visibleBody(c), states.getValue(c.id), descendantCount(c.id, childrenOf), replies,
                revs[c.id]?.takeIf { pending.all || caller?.accountId == c.authorId }, files[c.id].orEmpty(), pending,
                post.status == PostStatus.PUBLISHED,
            )
        val threads = roots.values.map { root ->
            view(root, repliesOf[root.id].orEmpty().map { view(it, emptyList()) })
        }
        return PageResult(threads, roots.totalElements)
    }

    /**
     * 이 댓글(또는 대기 중인 고친 판)의 첨부를 짧은 서명 주소로 본다 - 공개 영역에 없는 사진(승인 대기 · 판)을 보는 길.
     * 운영자 · 댓글을 관리하는 글 주인(비회원 글 주인은 글의 비밀) · 댓글 작성자 본인(비회원은 댓글의 비밀)만. 못 보는 댓글은 404, 그 댓글의 첨부가 아닌 키도 404.
     */
    fun attachmentAccess(caller: BoardCaller?, boardCode: String, postId: Long, commentId: Long, key: String, guest: GuestContext? = null): SignedAttachment {
        val board = access.board(boardCode)
        val post = access.post(caller, boardCode, postId)
        val comment = access.comment(post, commentId)
        if (!pendingVisibility(caller, board, post).sees(comment)) notFound(commentId)
        val allowed = if (caller != null) {
            policy.canManageComments(caller, post, board) || caller.accountId == comment.authorId
        } else {
            val ctx = guestWriter(board, guest)
            guests.admit(ctx, checkCaptcha = false)
            (board.settings.ownerModeratesComments && guests.verify(ctx, "p${post.id}", access.guestSecretHash(post))) ||
                (BoardAuthors.isGuest(comment.authorId) && guests.verify(ctx, "c${comment.id}", comments.guestSecretHash(comment.id)))
        }
        if (!allowed) throw BoardException(BoardErrorCode.FORBIDDEN, BoardGuests.FORBIDDEN_MESSAGE)
        val keys = attachmentsOf(listOf(comment.id))[comment.id].orEmpty() + revisions.find(RevisionTarget.COMMENT, listOf(comment.id))[comment.id]?.attachments.orEmpty()
        if (key !in keys) notFound(commentId)
        return uploads.signed(key) ?: notFound(commentId)
    }

    fun update(
        caller: BoardCaller?, boardCode: String, postId: Long, commentId: Long, body: String?, guest: GuestContext? = null, attachments: List<String>? = null,
    ): CommentView {
        val post = access.post(caller, boardCode, postId)
        if (caller == null) return updateAsGuest(boardCode, post, commentId, body, guest, attachments)
        val comment = access.comment(post, commentId)
        val board = access.board(boardCode)
        if (!pendingVisibility(caller, board, post).sees(comment)) notFound(commentId)   // 못 보는 행(거절 · 대기 중 지움)은 없는 것과 같은 답
        if (!policy.canEditComment(caller, comment)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to edit comment $commentId")
        val text = editedText(body, attachments, comment)
        // 첨부를 끈 게시판(상한 0)에서는 빈 목록도 「첨부를 건드리지 않음」이다 - 첨부를 모르는 저장소가 빈 목록에 500 을 내지 않고, 저장된 옛 첨부를 지우지도 않는다
        val keys = attachments?.let { rules.commentAttachments(it) }?.takeIf { properties.commentMaxAttachments > 0 }
        val existing = if (revisionsOn(board, post)) revisions.find(RevisionTarget.COMMENT, listOf(comment.id))[comment.id] else null
        if (keys != null) {
            val known = attachmentsOf(listOf(comment.id))[comment.id].orEmpty() + existing?.attachments.orEmpty()
            authorRules.rejectAccountIdKeys(caller, keys.filterNot { it in known })   // 옛 키를 그대로 다시 보낸 수정은 통과 — 새 키만 본다
            if (keys.isNotEmpty() && !policy.canAttach(caller, keys)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to attach these files")
        }
        val pending = pendingVisibility(caller, board, post)
        // 승인 대상 작성자가 공개된 댓글을 고치면 공개본은 그대로 두고 고친 내용만 판으로 대기한다
        if (comment.status == CommentStatus.PUBLISHED && policy.needsApproval(board, post, caller, ApprovalTarget.COMMENT)) {
            val rev = submitRevision(board, post, comment, text, keys, actorOf(caller))
            return single(caller, boardCode, post, comment, pending, rev)
        }
        val hadRevision = existing != null
        val updated = comments.updateContent(comment.id, text, keys, time.now()) ?: notFound(commentId)
        events.publish(CommentUpdated(updated, boardCode, actorOf(caller)))
        if (hadRevision) announceDiscard(boardCode, updated, actorOf(caller))   // 판은 저장소가 같은 트랜잭션에서 지웠다
        return single(caller, boardCode, post, updated, pending)
    }

    /** 본문이 없고 첨부만 보낸 수정은 본문을 그대로 둔다 */
    private fun editedText(body: String?, attachments: List<String>?, comment: Comment): String =
        if (body == null && attachments != null) comment.body else rules.commentBody(body)

    private fun updateAsGuest(boardCode: String, post: Post, commentId: Long, body: String?, guest: GuestContext?, attachments: List<String>?): CommentView {
        val ctx = guestWriter(access.board(boardCode), guest)
        guests.admit(ctx, checkCaptcha = false)
        val comment = guestOwned(post, commentId, ctx)
        if (comment.status !in GUEST_EDITABLE) throw BoardException(BoardErrorCode.FORBIDDEN, BoardGuests.FORBIDDEN_MESSAGE)
        val board = access.board(boardCode)
        val text = editedText(body, attachments, comment)
        val keys = attachments?.let { rules.commentAttachments(it) }?.takeIf { properties.commentMaxAttachments > 0 }
        val existing = if (revisionsOn(board, post)) revisions.find(RevisionTarget.COMMENT, listOf(comment.id))[comment.id] else null
        if (!keys.isNullOrEmpty()) {
            val known = attachmentsOf(listOf(comment.id))[comment.id].orEmpty() + existing?.attachments.orEmpty()
            if (!policy.canGuestAttach(keys.filterNot { it in known })) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to attach these files")
        }
        if (comment.status == CommentStatus.PUBLISHED && policy.needsApproval(board, post, null, ApprovalTarget.COMMENT)) {
            val rev = submitRevision(board, post, comment, text, keys, GUEST)
            return single(null, boardCode, post, comment, PendingVisibility.NONE, rev)   // 비회원은 다시 읽을 때 판을 보지 못한다 — 이 응답을 화면이 들고 있는다
        }
        val hadRevision = existing != null
        val updated = comments.updateContent(comment.id, text, keys, time.now()) ?: notFound(commentId)
        events.publish(CommentUpdated(updated, boardCode, GUEST))
        if (hadRevision) announceDiscard(boardCode, updated, GUEST)
        return single(null, boardCode, post, updated, PendingVisibility.NONE)
    }

    fun delete(caller: BoardCaller?, boardCode: String, postId: Long, commentId: Long, guest: GuestContext? = null) {
        val post = access.post(caller, boardCode, postId)
        if (caller == null) return deleteAsGuest(boardCode, post, commentId, guest)
        val comment = access.comment(post, commentId)
        if (!pendingVisibility(caller, access.board(boardCode), post).sees(comment)) notFound(commentId)
        if (!policy.canDeleteComment(caller, comment)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to delete comment $commentId")
        if (comment.status != CommentStatus.DELETED) {
            val dropped = hadRevisionBeingDropped(comment, CommentStatus.DELETED)
            comments.setStatus(comment.id, CommentStatus.DELETED, time.now())?.let {
                events.publish(CommentStatusChanged(it, boardCode, comment.status, CommentStatus.DELETED, actorOf(caller)))
                if (dropped) announceDiscard(boardCode, it, actorOf(caller))
            }
        }
    }

    private fun deleteAsGuest(boardCode: String, post: Post, commentId: Long, guest: GuestContext?) {
        val ctx = guestWriter(access.board(boardCode), guest)
        guests.admit(ctx, checkCaptcha = false)
        val comment = guestOwned(post, commentId, ctx)
        if (comment.status != CommentStatus.DELETED) {
            val dropped = hadRevisionBeingDropped(comment, CommentStatus.DELETED)
            comments.setStatus(comment.id, CommentStatus.DELETED, time.now())?.let {
                events.publish(CommentStatusChanged(it, boardCode, comment.status, CommentStatus.DELETED, GUEST))
                if (dropped) announceDiscard(boardCode, it, GUEST)
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
            // 가려진 댓글은 **주인이 직접 가린 것**(moderatedBy == OWNER)만 다루고, 운영자가 가렸거나 누가 가렸는지 모르는 옛 행은 목표 상태와 무관하게 막는다 (닫힌 쪽)
            val blocked = status == CommentStatus.DELETED || comment.status == CommentStatus.DELETED ||
                (comment.status == CommentStatus.HIDDEN && comment.moderatedBy != ModeratedBy.OWNER)
            if (blocked) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to moderate comment $commentId")
        }
        val dropped = hadRevisionBeingDropped(comment, status)
        val updated = comments.setStatus(comment.id, status, time.now(), by) ?: notFound(commentId)
        if (dropped) announceDiscard(boardCode, updated, BoardActor(if (by == ModeratedBy.OWNER) BoardActorKind.OWNER else BoardActorKind.MODERATOR, caller?.accountId))
        if (updated.status != comment.status) {
            events.publish(CommentStatusChanged(updated, boardCode, comment.status, updated.status, BoardActor(if (by == ModeratedBy.OWNER) BoardActorKind.OWNER else BoardActorKind.MODERATOR, caller?.accountId)))
            if (comment.status == CommentStatus.PENDING && updated.status == CommentStatus.PUBLISHED) notifyApproved(post, updated)
        }
        return single(caller, boardCode, post, updated, pendingVisibility(caller, board, post))
    }

    /** 승인 대기 댓글 목록(오래된 순, 평평하게) — 댓글을 관리하는 사람(운영자 · 글 주인)에게만 */
    fun listPending(caller: BoardCaller?, boardCode: String, postId: Long, page: Int, size: Int, guest: GuestContext? = null): PageResult<CommentView> {
        val board = access.board(boardCode)
        val post = access.post(caller, boardCode, postId)
        manager(caller, board, post, guest, countsAsWrite = false)
        val rows = comments.pendingPage(post.id, page, properties.pageSize(size))
        if (rows.values.isEmpty()) return PageResult(emptyList(), rows.totalElements)
        val states = reactions.states(ReactionTarget.COMMENT, rows.values.map { it.id }, caller)
        val active = authorRules.active
        val publicIds = rows.values.associate { it.id to publicId(it, active) }
        val names = authors.of(boardCode, publicIds.values)
        return PageResult(
            rows.values.let { list -> val files = attachmentsOf(list.map { it.id }); list.map { view(caller, it, publicIds.getValue(it.id), card(it, names[publicIds.getValue(it.id)]), it.body, states.getValue(it.id), 0, attachments = files[it.id].orEmpty(), pending = PendingVisibility(all = true)) } },
            rows.totalElements,
        )
    }

    /** 판 승인 · 거절 — 댓글을 관리하는 쪽(운영자 · 글 주인)만. 승인이면 공개본이 바뀌므로 `CommentUpdated` 도 나간다 */
    fun decideRevision(
        caller: BoardCaller?, boardCode: String, postId: Long, commentId: Long, decision: RevisionDecision, version: Instant?, guest: GuestContext? = null,
    ): CommentView {
        val board = access.board(boardCode)
        val post = access.post(caller, boardCode, postId)
        val by = manager(caller, board, post, guest)
        val comment = access.comment(post, commentId)
        val seen = version ?: throw BoardException(BoardErrorCode.CONTENT_INVALID, "submittedAt (the version of the edit you reviewed) is required")
        val actor = BoardActor(if (by == ModeratedBy.OWNER) BoardActorKind.OWNER else BoardActorKind.MODERATOR, caller?.accountId)
        val none = BoardException(BoardErrorCode.COMMENT_NOT_FOUND, "No pending revision for comment $commentId")
        val changed = BoardException(BoardErrorCode.REVISION_CHANGED, "The edit changed while you were reviewing it - review it again")
        var current = comment
        val outcome = if (decision == RevisionDecision.APPROVE) {
            when (revisions.apply(RevisionTarget.COMMENT, comment.id, time.now(), seen)) {
                RevisionWrite.NONE -> throw none
                RevisionWrite.CHANGED -> throw changed
                RevisionWrite.DONE -> Unit
            }
            current = comments.find(comment.id) ?: notFound(commentId)
            events.publish(CommentUpdated(current, boardCode, actor))
            RevisionOutcome.APPROVED
        } else {
            when (revisions.reject(RevisionTarget.COMMENT, comment.id, seen)) {
                RevisionWrite.NONE -> throw none
                RevisionWrite.CHANGED -> throw changed
                RevisionWrite.DONE -> Unit
            }
            RevisionOutcome.REJECTED
        }
        events.publish(RevisionDecided(RevisionTarget.COMMENT, comment.id, boardCode, post.id, outcome, actor))
        return single(caller, boardCode, post, current, pendingVisibility(caller, board, post))
    }

    /** 고친 댓글 목록(공개본 + 대기 판) — 댓글을 관리하는 쪽에게만, 오래된 순 평평하게 */
    fun listRevisions(caller: BoardCaller?, boardCode: String, postId: Long, page: Int, size: Int, guest: GuestContext? = null): PageResult<CommentView> {
        val board = access.board(boardCode)
        val post = access.post(caller, boardCode, postId)
        manager(caller, board, post, guest, countsAsWrite = false)
        val revs = revisions.pending(RevisionTarget.COMMENT, null, post.id, page, properties.pageSize(size))
        val rows = revs.values.mapNotNull { r -> comments.find(r.targetId)?.let { it to r } }
        if (rows.isEmpty()) return PageResult(emptyList(), revs.totalElements)
        val states = reactions.states(ReactionTarget.COMMENT, rows.map { it.first.id }, caller)
        val active = authorRules.active
        val publicIds = rows.associate { it.first.id to publicId(it.first, active) }
        val names = authors.of(boardCode, publicIds.values)
        return PageResult(
            rows.let { list -> val files = attachmentsOf(list.map { it.first.id }); list.map { (c, r) -> view(caller, c, publicIds.getValue(c.id), card(c, names[publicIds.getValue(c.id)]), visibleBody(c), states.getValue(c.id), 0, revision = r, attachments = files[c.id].orEmpty(), pending = PendingVisibility(all = true)) } },
            revs.totalElements,
        )
    }

    private fun submitRevision(board: Board, post: Post, comment: Comment, text: String, keys: List<String>?, actor: BoardActor): Revision {
        val existing = revisions.find(RevisionTarget.COMMENT, listOf(comment.id))[comment.id]
        val now = time.now()
        // 저장된 공개 첨부를 설정과 무관하게 읽는다 - 첨부 상한을 0 으로 되돌린 뒤에도 본문만 고친 판이 승인될 때 첨부가 지워지지 않게
        val publicKeys = comments.attachments(listOf(comment.id))[comment.id].orEmpty()
        val rev = Revision(RevisionTarget.COMMENT, comment.id, board.code, post.id, null, text, keys ?: existing?.attachments ?: publicKeys, existing?.createdAt ?: now, now)
        val result = revisions.submit(rev)
        // 잠근 뒤 보니 댓글이 이미 가려졌거나 지워졌다 - 판을 넣지 않는다(없는 것과 같게 답한다)
        if (result == RevisionSubmit.TARGET_GONE) notFound(comment.id)
        events.publish(RevisionSubmitted(RevisionTarget.COMMENT, comment.id, board.code, post.id, result == RevisionSubmit.REPLACED, actor))
        notifyRevision(board, post, comment, text)
        return revisions.find(RevisionTarget.COMMENT, listOf(comment.id))[comment.id] ?: rev
    }

    /** 글마다 켠 댓글 승인(`post.commentApproval`)도 판을 만든다 - 게시판 설정이 꺼져 있어도 */
    private fun revisionsOn(board: Board, post: Post) =
        board.settings.postApproval != ApprovalMode.OFF || board.settings.commentApproval != ApprovalMode.OFF ||
            (post.commentApproval != null && post.commentApproval != ApprovalMode.OFF)

    private fun hadRevisionBeingDropped(comment: Comment, newStatus: CommentStatus): Boolean =
        comment.status == CommentStatus.PUBLISHED && newStatus != CommentStatus.PUBLISHED &&
            revisions.find(RevisionTarget.COMMENT, listOf(comment.id)).isNotEmpty()

    private fun announceDiscard(boardCode: String, comment: Comment, actor: BoardActor) =
        events.publish(RevisionDecided(RevisionTarget.COMMENT, comment.id, boardCode, comment.postId, RevisionOutcome.DISCARDED, actor))

    /** 고친 내용이 대기로 들어왔다 — 댓글을 관리하는 글 주인(회원)에게만 `comment.pending` (payload `revision: true`) */
    private fun notifyRevision(board: Board, post: Post, comment: Comment, revisionText: String) {
        val recipient = post.authorId
        if (!board.settings.ownerModeratesComments || recipient == comment.authorId || BoardAuthors.isGuest(recipient)) return
        try {
            val publicId = publicId(comment, authorRules.active)
            notifier.commentPending(BoardCommentNotice(recipient, post, comment, comment.parentId?.let { comments.find(it) }, publicId, card(comment, authors.one(board.code, publicId))?.name, revision = true, revisionBody = revisionText))
        } catch (e: Exception) {
            log.warn("board revision notification failed (comment {}): {}", comment.id, e.toString())
        }
    }

    /**
     * 이 글의 댓글을 관리하는 쪽: 운영자 → [ModeratedBy.MODERATOR], 댓글 관리가 켜진 게시판의 글 주인 → [ModeratedBy.OWNER].
     * 비회원 글 주인은 비밀이 글의 것과 맞아야 한다 (대입 한도는 해시 전에). 아니면 403 — 로그인하지 않은 쪽이 비회원도 못 쓰는 요청이면 401.
     */
    private fun manager(caller: BoardCaller?, board: Board, post: Post, guest: GuestContext?, countsAsWrite: Boolean = true): ModeratedBy {
        if (caller != null) {
            if (policy.canModerate(caller)) return ModeratedBy.MODERATOR
            if (policy.canManageComments(caller, post, board)) return ModeratedBy.OWNER
            throw BoardException(BoardErrorCode.FORBIDDEN, "Only moderators and managing post owners moderate")
        }
        val ctx = guestWriter(board, guest)
        if (countsAsWrite) guests.admit(ctx, checkCaptcha = false)   // 목록 읽기는 쓰기 한도를 먹지 않는다 (비밀 대입 한도는 그대로)
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
        if (comment.status == CommentStatus.PENDING || !comment.everPublished) notFound(commentId)   // 한 번도 공개된 적 없는 행은 존재를 알리지 않는다
        throw BoardException(BoardErrorCode.FORBIDDEN, BoardGuests.FORBIDDEN_MESSAGE)
    }

    private fun card(c: Comment, fromDirectory: AuthorCard?): AuthorCard? = if (BoardAuthors.isGuest(c.authorId)) AuthorCard(c.guestName) else fromDirectory

    private fun single(caller: BoardCaller?, boardCode: String, post: Post, comment: Comment, pending: PendingVisibility, revision: Revision? = null): CommentView {
        val shown = revision ?: if (caller != null && (pending.all || caller.accountId == comment.authorId)) revisions.find(RevisionTarget.COMMENT, listOf(comment.id))[comment.id] else null
        val childrenOf = comments.descendants(listOf(comment.rootId)).filter { pending.sees(it) }.groupBy { it.parentId }
        val publicId = publicId(comment, authorRules.active)
        return view(
            caller, comment, publicId, card(comment, authors.one(boardCode, publicId)), visibleBody(comment),
            reactions.state(ReactionTarget.COMMENT, comment.id, caller), descendantCount(comment.id, childrenOf), revision = shown, attachments = attachmentsOf(listOf(comment.id))[comment.id].orEmpty(), pending = pending,
            postPublic = post.status == PostStatus.PUBLISHED,
        )
    }

    private fun publicId(c: Comment, active: Boolean) = BoardAuthors.publicId(c.authorRef, c.authorId, BoardAuthors.COMMENT_ROW, c.id, active)

    private fun view(
        caller: BoardCaller?, c: Comment, publicId: String, card: AuthorCard?, body: String?,
        state: ReactionState, replyCount: Int, replies: List<CommentView> = emptyList(), revision: Revision? = null, attachments: List<String> = emptyList(), pending: PendingVisibility? = null, postPublic: Boolean = false,
    ) = CommentView(
        c, publicId, BoardAuthors.isDeleted(c.authorId) || card?.deleted == true, caller?.accountId == c.authorId, body, state, replyCount, replies, card, revision?.view(),
        // 가린 · 지운 댓글의 사진은 본문처럼 나가지 않는다
        if (body != null) attachments else emptyList(),
        canManage = if (caller != null && pending?.all == true) caller.moderator || ownerMayTouch(c) else null,
        attachmentUrls = if (postPublic && body != null && c.status == CommentStatus.PUBLISHED) uploads.publicUrls(attachments) else emptyMap(),
    )

    /** 운영자가 아닌 관리자(글 주인)가 이 댓글의 상태를 바꿀 수 있는가 - [moderate] 의 막는 규칙과 같다(운영자가 가렸거나 모르는 옛 행 · 삭제된 행은 못 건드린다) */
    private fun ownerMayTouch(c: Comment) = c.status != CommentStatus.DELETED && !(c.status == CommentStatus.HIDDEN && c.moderatedBy != ModeratedBy.OWNER)

    /** 댓글 첨부는 켜져 있을 때만 읽는다 (기본 꺼짐 — 기존 쿼리 수 그대로). 쪽의 모든 댓글을 쿼리 한 번으로 */
    private fun attachmentsOf(ids: Collection<Long>): Map<Long, List<String>> =
        if (properties.commentMaxAttachments > 0 && ids.isNotEmpty()) comments.attachments(ids) else emptyMap()

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
