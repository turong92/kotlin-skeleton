package dev.sumin.skeleton.board

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
    private val policy: BoardPolicy,
    private val rules: BoardContentRules,
    private val properties: BoardProperties,
    private val limiter: BoardRateLimiter,
    private val notifier: BoardNotifier,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(CommentService::class.java)

    fun create(caller: BoardCaller, boardCode: String, postId: Long, parentId: Long?, body: String?): CommentView {
        val post = access.post(caller, boardCode, postId)
        if (!policy.canComment(caller, post)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to comment")
        if (post.status != PostStatus.PUBLISHED) throw BoardException(BoardErrorCode.POST_NOT_COMMENTABLE, "Post $postId does not take comments")
        val parent = parentId?.let { access.comment(post, it) }
        if (parent != null && parent.status != CommentStatus.PUBLISHED) {
            throw BoardException(BoardErrorCode.POST_NOT_COMMENTABLE, "Comment ${parent.id} does not take replies")
        }
        val depth = (parent?.depth ?: -1) + 1
        if (depth > properties.maxCommentDepth) {
            throw BoardException(BoardErrorCode.COMMENT_TOO_DEEP, "Replies can be nested ${properties.maxCommentDepth} levels below a top-level comment")
        }
        val text = rules.commentBody(body)
        if (!limiter.tryAcquire(caller.accountId, "comment")) throw BoardException(BoardErrorCode.RATE_LIMITED, "Too many comments, slow down")
        val created = comments.insert(NewComment(post.id, parent?.id, parent?.rootId, depth, caller.accountId, text, time.now()))
            ?: throw BoardException(BoardErrorCode.POST_NOT_FOUND, "Post not found: $postId")
        notify(post, created, parent)
        return CommentView(created, created.body, reactions.state(ReactionTarget.COMMENT, created.id, caller), 0)
    }

    fun list(caller: BoardCaller?, boardCode: String, postId: Long, page: Int, size: Int, sort: CommentSort): PageResult<CommentView> {
        val post = access.post(caller, boardCode, postId)
        val roots = comments.rootPage(CommentRootQuery(post.id, page, properties.pageSize(size), sort))
        if (roots.values.isEmpty()) return PageResult(emptyList(), roots.totalElements)
        val descendants = comments.descendants(roots.values.map { it.id })
        val states = reactions.states(ReactionTarget.COMMENT, (roots.values + descendants).map { it.id }, caller)
        val childrenOf = descendants.groupBy { it.parentId }
        val repliesOf = descendants.groupBy { it.rootId }
        fun view(c: Comment, replies: List<CommentView>) =
            CommentView(c, visibleBody(c), states.getValue(c.id), descendantCount(c.id, childrenOf), replies)
        val threads = roots.values.map { root ->
            view(root, repliesOf[root.id].orEmpty().map { view(it, emptyList()) })
        }
        return PageResult(threads, roots.totalElements)
    }

    fun update(caller: BoardCaller, boardCode: String, postId: Long, commentId: Long, body: String?): CommentView {
        val post = access.post(caller, boardCode, postId)
        val comment = access.comment(post, commentId)
        if (!policy.canEditComment(caller, comment)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to edit comment $commentId")
        val updated = comments.updateBody(comment.id, rules.commentBody(body), time.now()) ?: notFound(commentId)
        return single(caller, updated)
    }

    fun delete(caller: BoardCaller, boardCode: String, postId: Long, commentId: Long) {
        val post = access.post(caller, boardCode, postId)
        val comment = access.comment(post, commentId)
        if (!policy.canDeleteComment(caller, comment)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to delete comment $commentId")
        if (comment.status != CommentStatus.DELETED) comments.setStatus(comment.id, CommentStatus.DELETED, time.now())
    }

    fun moderate(caller: BoardCaller, boardCode: String, postId: Long, commentId: Long, status: CommentStatus): CommentView {
        if (!policy.canModerate(caller)) throw BoardException(BoardErrorCode.FORBIDDEN, "Only moderators moderate")
        val post = access.post(caller, boardCode, postId)
        val comment = access.comment(post, commentId)
        val updated = comments.setStatus(comment.id, status, time.now()) ?: notFound(commentId)
        return single(caller, updated)
    }

    private fun single(caller: BoardCaller, comment: Comment): CommentView {
        val childrenOf = comments.descendants(listOf(comment.rootId)).groupBy { it.parentId }
        return CommentView(comment, visibleBody(comment), reactions.state(ReactionTarget.COMMENT, comment.id, caller), descendantCount(comment.id, childrenOf))
    }

    private fun visibleBody(c: Comment): String? = c.body.takeIf { c.status == CommentStatus.PUBLISHED }

    private fun descendantCount(id: Long, childrenOf: Map<Long?, List<Comment>>): Int =
        childrenOf[id].orEmpty().sumOf { 1 + descendantCount(it.id, childrenOf) }

    /** 답글이면 부모 댓글의 작성자에게, 최상위 댓글이면 글 작성자에게 — 자기 자신에게는 보내지 않는다. 알림 실패는 댓글 작성을 막지 않는다. */
    private fun notify(post: Post, comment: Comment, parent: Comment?) {
        val recipient = parent?.authorId ?: post.authorId
        if (recipient == comment.authorId) return
        try {
            notifier.commentCreated(BoardCommentNotice(recipient, post, comment, parent))
        } catch (e: Exception) {
            log.warn("board comment notification failed (comment {}): {}", comment.id, e.toString())
        }
    }

    private fun notFound(id: Long): Nothing = throw BoardException(BoardErrorCode.COMMENT_NOT_FOUND, "Comment not found: $id")
}
