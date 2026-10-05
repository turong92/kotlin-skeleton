package dev.sumin.skeleton.board

/**
 * 누가 무엇을 할 수 있는지 — 앱이 같은 타입의 빈을 만들면 이 기본 구현이 물러난다.
 * 상태 규칙(숨겨진 글에는 댓글을 못 단다 등)은 서비스가 따로 본다. 여기는 사람(호출자)만 본다.
 */
interface BoardPolicy {
    fun canCreateBoard(caller: BoardCaller): Boolean

    fun canCreatePost(caller: BoardCaller, board: Board): Boolean

    /** 읽을 수 있는지. 못 읽으면 존재를 알려 주지 않으려고 404 로 답한다 */
    fun canViewPost(caller: BoardCaller?, post: Post): Boolean

    fun canEditPost(caller: BoardCaller, post: Post): Boolean

    fun canDeletePost(caller: BoardCaller, post: Post): Boolean

    /** 숨기기 · 고정 · 상태 되돌리기, 그리고 목록에서 PUBLISHED 가 아닌 상태 보기 */
    fun canModerate(caller: BoardCaller): Boolean

    fun canComment(caller: BoardCaller, post: Post): Boolean

    fun canEditComment(caller: BoardCaller, comment: Comment): Boolean

    fun canDeleteComment(caller: BoardCaller, comment: Comment): Boolean

    fun canReact(caller: BoardCaller): Boolean

    /** 첨부 키를 이 사람이 붙여도 되는지 — 기본은 허용 (키 규칙은 앱의 저장소 설정이 안다: 예 `<접두사>/<계정 id>/…`) */
    fun canAttach(caller: BoardCaller, keys: List<String>): Boolean
}

class DefaultBoardPolicy : BoardPolicy {
    override fun canCreateBoard(caller: BoardCaller): Boolean = caller.moderator

    override fun canCreatePost(caller: BoardCaller, board: Board): Boolean = true

    override fun canViewPost(caller: BoardCaller?, post: Post): Boolean =
        post.status == PostStatus.PUBLISHED || (caller != null && (caller.moderator || caller.accountId == post.authorId))

    override fun canEditPost(caller: BoardCaller, post: Post): Boolean =
        caller.moderator || (caller.accountId == post.authorId && post.status in EDITABLE_BY_AUTHOR)

    override fun canDeletePost(caller: BoardCaller, post: Post): Boolean =
        caller.moderator || caller.accountId == post.authorId

    override fun canModerate(caller: BoardCaller): Boolean = caller.moderator

    override fun canComment(caller: BoardCaller, post: Post): Boolean = true

    override fun canEditComment(caller: BoardCaller, comment: Comment): Boolean =
        caller.accountId == comment.authorId && comment.status == CommentStatus.PUBLISHED

    override fun canDeleteComment(caller: BoardCaller, comment: Comment): Boolean =
        caller.moderator || caller.accountId == comment.authorId

    override fun canReact(caller: BoardCaller): Boolean = true

    override fun canAttach(caller: BoardCaller, keys: List<String>): Boolean = true

    private companion object {
        val EDITABLE_BY_AUTHOR = setOf(PostStatus.DRAFT, PostStatus.PUBLISHED)
    }
}
