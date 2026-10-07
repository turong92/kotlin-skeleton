package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorDirectory

/** 서비스가 돌려주는 읽기 모양 — 컨트롤러가 DTO 로 바꾼다. 반응 개수는 설정된 종류 전부를 (0 포함) 담는다. */
data class PostSummaryView(val item: PostListItem, val reactions: ReactionState, val author: AuthorCard? = null)

data class PostDetailView(val post: Post, val reactions: ReactionState, val author: AuthorCard? = null)

/** 최상위 댓글이면 [replies] 에 모든 자손이 작성 순으로 평평하게 들어 있다 (답글의 replies 는 항상 비어 있다). body 는 PUBLISHED 가 아니면 null. */
data class CommentView(
    val comment: Comment,
    val body: String?,
    val reactions: ReactionState,
    val replyCount: Int,
    val replies: List<CommentView> = emptyList(),
    /** 작성자 이름 · 꼬리표 — [AuthorDirectory] 가 모르거나 지워진 작성자면 null */
    val author: AuthorCard? = null,
)

data class BoardConfigView(
    val reactionTypes: List<String>,
    val reactionMode: ReactionMode,
    val maxCommentDepth: Int,
    val titleMaxLength: Int,
    val bodyMaxLength: Int,
    val commentMaxLength: Int,
    val maxPageSize: Int,
    val canModerate: Boolean,
)

data class CreatePost(val title: String?, val body: String?, val attachments: List<String>?, val status: PostStatus?)

data class UpdatePost(val title: String?, val body: String?, val attachments: List<String>?, val status: PostStatus?)

data class PostListRequest(
    val page: Int,
    val size: Int,
    val sort: PostSort = PostSort.LATEST,
    val q: String? = null,
    val reaction: String? = null,
    val status: PostStatus? = null,
    val mine: Boolean = false,
)

/** 댓글이 달렸을 때 알림 받을 사람과 그 맥락. 보내는 쪽(notification 모듈)은 [BoardNotifier] 구현이 안다. */
data class BoardCommentNotice(val recipientId: String, val post: Post, val comment: Comment, val parent: Comment?, val authorName: String? = null)

fun interface BoardNotifier {
    fun commentCreated(notice: BoardCommentNotice)
}

object NoopBoardNotifier : BoardNotifier {
    override fun commentCreated(notice: BoardCommentNotice) = Unit
}
