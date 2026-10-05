package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.time.TimeProvider

/** 글 · 댓글에 반응을 달고 뗀다. 종류는 설정(`skeleton.board.reaction.types`)이 정하고, 같은 사람의 한 대상 반응이 몇 개일 수 있는지는 mode 가 정한다. */
class ReactionService(
    private val access: BoardAccess,
    private val comments: CommentRepository,
    private val reactions: ReactionRepository,
    private val support: ReactionSupport,
    private val policy: BoardPolicy,
    private val properties: BoardProperties,
    private val limiter: BoardRateLimiter,
    private val time: TimeProvider,
) {
    fun reactToPost(caller: BoardCaller, boardCode: String, postId: Long, type: String?): ReactionState {
        val code = support.requireType(type)
        val post = access.post(caller, boardCode, postId)
        open(caller, post.status == PostStatus.PUBLISHED)
        if (!reactions.react(ReactionTarget.POST, post.id, caller.accountId, code, properties.reaction.mode, time.now())) {
            throw BoardException(BoardErrorCode.POST_NOT_FOUND, "Post not found: $postId")
        }
        return support.state(ReactionTarget.POST, post.id, caller)
    }

    fun removeFromPost(caller: BoardCaller, boardCode: String, postId: Long, type: String?): ReactionState {
        val code = type?.let { support.requireType(it) }
        val post = access.post(caller, boardCode, postId)
        if (!reactions.remove(ReactionTarget.POST, post.id, caller.accountId, code)) {
            throw BoardException(BoardErrorCode.POST_NOT_FOUND, "Post not found: $postId")
        }
        return support.state(ReactionTarget.POST, post.id, caller)
    }

    fun reactToComment(caller: BoardCaller, boardCode: String, postId: Long, commentId: Long, type: String?): ReactionState {
        val code = support.requireType(type)
        val post = access.post(caller, boardCode, postId)
        val comment = access.comment(post, commentId)
        open(caller, post.status == PostStatus.PUBLISHED && comment.status == CommentStatus.PUBLISHED)
        if (!reactions.react(ReactionTarget.COMMENT, comment.id, caller.accountId, code, properties.reaction.mode, time.now())) {
            throw BoardException(BoardErrorCode.COMMENT_NOT_FOUND, "Comment not found: $commentId")
        }
        return support.state(ReactionTarget.COMMENT, comment.id, caller)
    }

    fun removeFromComment(caller: BoardCaller, boardCode: String, postId: Long, commentId: Long, type: String?): ReactionState {
        val code = type?.let { support.requireType(it) }
        val post = access.post(caller, boardCode, postId)
        val comment = access.comment(post, commentId)
        if (!reactions.remove(ReactionTarget.COMMENT, comment.id, caller.accountId, code)) {
            throw BoardException(BoardErrorCode.COMMENT_NOT_FOUND, "Comment not found: $commentId")
        }
        return support.state(ReactionTarget.COMMENT, comment.id, caller)
    }

    private fun open(caller: BoardCaller, targetOpen: Boolean) {
        if (!policy.canReact(caller)) throw BoardException(BoardErrorCode.FORBIDDEN, "Not allowed to react")
        if (!targetOpen) throw BoardException(BoardErrorCode.POST_NOT_COMMENTABLE, "Only published posts and comments take reactions")
        if (!limiter.tryAcquire(caller.accountId, "reaction")) throw BoardException(BoardErrorCode.RATE_LIMITED, "Too many reactions, slow down")
    }
}
