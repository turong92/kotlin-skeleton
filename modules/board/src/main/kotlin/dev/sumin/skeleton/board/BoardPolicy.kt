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

    /** 로그인하지 않은 사람이 이 게시판에 글을 쓸 수 있는가 — 게시판의 `guest_write` 가 ALL 일 때. 켜는 것은 앱(`skeleton.board.guest.enabled`) */
    fun canGuestPost(board: Board): Boolean = board.settings.guestWrite == GuestWrite.ALL

    /** 로그인하지 않은 사람이 이 글에 댓글을 달 수 있는가 — `guest_write` 가 NONE 이 아닐 때 */
    fun canGuestComment(board: Board, post: Post): Boolean = board.settings.guestWrite != GuestWrite.NONE

    /**
     * 비회원이 붙이는 첨부 키를 허락하는가 — **기본 true (회원의 [canAttach] 기본과 같다)**. 비회원은 소유를 확인할 계정이 없어 아무 키나 실을 수 있다:
     * 업로드를 받는 앱은 이 메서드로 앱이 발급한 키만 통과시킨다 (docs/modules/board.md 「비회원 첨부」).
     */
    fun canGuestAttach(keys: List<String>): Boolean = true
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
        caller.accountId == comment.authorId && comment.status in setOf(CommentStatus.PENDING, CommentStatus.PUBLISHED)

    override fun canDeleteComment(caller: BoardCaller, comment: Comment): Boolean =
        caller.moderator || caller.accountId == comment.authorId

    override fun canReact(caller: BoardCaller): Boolean = true

    override fun canAttach(caller: BoardCaller, keys: List<String>): Boolean = true

    private companion object {
        val EDITABLE_BY_AUTHOR = setOf(PostStatus.DRAFT, PostStatus.PENDING, PostStatus.PUBLISHED)
    }
}
