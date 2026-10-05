package dev.sumin.skeleton.board

/** 서비스들이 함께 쓰는 조회 — 게시판 · 글 · 댓글을 찾고, 호출자가 못 보는 것은 없는 것과 같은 404 로 답한다. */
class BoardAccess(
    private val boards: BoardRepository,
    private val posts: PostRepository,
    private val comments: CommentRepository,
    private val policy: BoardPolicy,
) {
    fun board(code: String): Board = boards.find(code) ?: throw BoardException(BoardErrorCode.NOT_FOUND, "Board not found: $code")

    fun post(caller: BoardCaller?, code: String, postId: Long): Post {
        board(code)
        val post = posts.find(postId)
        if (post == null || post.boardCode != code || !policy.canViewPost(caller, post)) {
            throw BoardException(BoardErrorCode.POST_NOT_FOUND, "Post not found: $postId")
        }
        return post
    }

    fun comment(post: Post, commentId: Long): Comment =
        comments.find(commentId)?.takeIf { it.postId == post.id }
            ?: throw BoardException(BoardErrorCode.COMMENT_NOT_FOUND, "Comment not found: $commentId")
}
