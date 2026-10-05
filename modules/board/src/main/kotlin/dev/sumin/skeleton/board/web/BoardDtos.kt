package dev.sumin.skeleton.board.web

import dev.sumin.skeleton.board.BoardAuthors
import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.board.Board
import dev.sumin.skeleton.board.BoardConfigView
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.CommentView
import dev.sumin.skeleton.board.PostDetailView
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.PostSummaryView
import dev.sumin.skeleton.board.ReactionMode
import dev.sumin.skeleton.board.ReactionState
import java.time.Instant

data class BoardConfigResponse(
    val reactionTypes: List<String>,
    val reactionMode: ReactionMode,
    val maxCommentDepth: Int,
    val titleMaxLength: Int,
    val bodyMaxLength: Int,
    val commentMaxLength: Int,
    val maxPageSize: Int,
    val canModerate: Boolean,
)

data class BoardResponse(val code: String, val name: String, val description: String?, val postCount: Long, val createdAt: Instant)

data class PostSummaryResponse(
    val id: Long,
    val boardCode: String,
    val authorId: String,
    val title: String,
    val excerpt: String,
    val status: PostStatus,
    val pinned: Boolean,
    val viewCount: Long,
    val commentCount: Long,
    val reactionCounts: Map<String, Long>,
    val myReactions: Set<String>,
    val attachmentCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 작성자의 계정이 지워졌다 — 화면은 이름 대신 "삭제된 사용자" 를 보인다 */
    val authorDeleted: Boolean = BoardAuthors.isDeleted(authorId),
)

data class PostDetailResponse(
    val id: Long,
    val boardCode: String,
    val authorId: String,
    val title: String,
    val excerpt: String,
    val body: String,
    val attachments: List<String>,
    val status: PostStatus,
    val pinned: Boolean,
    val viewCount: Long,
    val commentCount: Long,
    val reactionCounts: Map<String, Long>,
    val myReactions: Set<String>,
    val attachmentCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 작성자의 계정이 지워졌다 — 화면은 이름 대신 "삭제된 사용자" 를 보인다 */
    val authorDeleted: Boolean = BoardAuthors.isDeleted(authorId),
)

/** 목록의 한 줄이면 replies 가 있다 (최상위: 모든 자손 작성 순 평평하게, 답글: 빈 배열). 한 댓글만 돌려주는 응답에는 replies 가 없다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CommentResponse(
    val id: Long,
    val postId: Long,
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val parentId: Long?,
    val rootId: Long,
    val depth: Int,
    val authorId: String,
    /** PUBLISHED 가 아니면 null — status 가 이유를 말한다 */
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val body: String?,
    val status: CommentStatus,
    val reactionCounts: Map<String, Long>,
    val myReactions: Set<String>,
    val replyCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val replies: List<CommentResponse>? = null,
    /** 작성자의 계정이 지워졌다 — 화면은 이름 대신 "삭제된 사용자" 를 보인다 */
    val authorDeleted: Boolean = BoardAuthors.isDeleted(authorId),
)

data class ReactionStateResponse(val counts: Map<String, Long>, val myReactions: Set<String>)

data class CreateBoardRequest(val code: String? = null, val name: String? = null, val description: String? = null)

data class CreatePostRequest(
    val title: String? = null,
    val body: String? = null,
    val attachments: List<String>? = null,
    val status: PostStatus? = null,
)

data class UpdatePostRequest(
    val title: String? = null,
    val body: String? = null,
    val attachments: List<String>? = null,
    val status: PostStatus? = null,
)

data class PostModerationRequest(val status: PostStatus? = null, val pinned: Boolean? = null)

data class CreateCommentRequest(val body: String? = null, val parentId: Long? = null)

data class UpdateCommentRequest(val body: String? = null)

data class CommentModerationRequest(val status: CommentStatus? = null)

data class ReactionRequest(val type: String? = null)

internal fun BoardConfigView.toResponse() =
    BoardConfigResponse(reactionTypes, reactionMode, maxCommentDepth, titleMaxLength, bodyMaxLength, commentMaxLength, maxPageSize, canModerate)

internal fun Board.toResponse() = BoardResponse(code, name, description, postCount, createdAt)

internal fun PostSummaryView.toResponse() = with(item) {
    PostSummaryResponse(
        id, boardCode, authorId, title, excerpt, status, pinned, viewCount, commentCount,
        reactions.counts, reactions.myReactions, attachmentCount, createdAt, updatedAt,
    )
}

internal fun PostDetailView.toResponse(excerptLength: Int) = with(post) {
    PostDetailResponse(
        id, boardCode, authorId, title, body.take(excerptLength), body, attachments, status, pinned, viewCount, commentCount,
        reactions.counts, reactions.myReactions, attachments.size, createdAt, updatedAt,
    )
}

/** [thread] 이면 목록의 한 줄 — `replies` 를 항상 낸다 (최상위는 자손 전부, 답글은 `[]`). 아니면 한 댓글만 돌려주는 응답이라 `replies` 가 없다. */
internal fun CommentView.toResponse(thread: Boolean = false): CommentResponse = with(comment) {
    CommentResponse(
        id, postId, parentId, rootId, depth, authorId, this@toResponse.body, status,
        reactions.counts, reactions.myReactions, replyCount, createdAt, updatedAt,
        replies = if (thread) replies.map { it.toResponse(thread = true) } else null,
    )
}

internal fun ReactionState.toResponse() = ReactionStateResponse(counts, myReactions)
