package dev.sumin.skeleton.board.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.board.ApprovalMode
import dev.sumin.skeleton.board.Board
import dev.sumin.skeleton.board.BoardAuthors
import dev.sumin.skeleton.board.BoardConfigView
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.CommentView
import dev.sumin.skeleton.board.GuestSecretMode
import dev.sumin.skeleton.board.GuestWrite
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
    /** true 면 글 · 댓글을 쓸 때 본문에 `authorId`(작성 프로필 id)가 필수다 */
    val authorRequired: Boolean,
    /** 비회원 쓰기가 켜져 있다 — 게시판마다 `settings.guestWrite` 가 실제로 정한다 */
    val guestEnabled: Boolean,
    /** 비회원 비밀의 종류 — PASSWORD(사람이 치는 값) · TOKEN(앱이 만든 무작위 값) */
    val guestSecret: GuestSecretMode,
    val guestNameMaxLength: Int,
    /** true 면 비회원의 만들기 본문에 `captchaToken` 이 필요하다 */
    val captchaRequired: Boolean,
)

data class BoardSettingsResponse(
    val guestWrite: GuestWrite,
    val postApproval: ApprovalMode,
    val commentApproval: ApprovalMode,
    val ownerModeratesComments: Boolean,
)

data class BoardResponse(
    val code: String,
    val name: String,
    val description: String?,
    val postCount: Long,
    val createdAt: Instant,
    val settings: BoardSettingsResponse,
)

data class PostSummaryResponse(
    val id: Long,
    val boardCode: String,
    /** 공개 작성자 id — `author-profile` 이 있는 앱에서는 프로필 id, 없으면 계정 id. 아직 넘겨받지 않은 옛 행은 `pending:…` */
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
    /** 작성자의 계정이나 프로필이 지워졌다 — 화면은 이름 대신 "삭제된 사용자" 를 보인다 */
    val authorDeleted: Boolean,
    /** 닉네임 — 계정 모듈(또는 앱의 AuthorDirectory)이 모르거나 지워진 작성자면 null. 화면은 이름이 없으면 authorDeleted · authorId 로 대신한다 */
    val authorName: String? = null,
    /** 같은 닉네임을 구분하는 4자리 꼬리표 (없으면 null) */
    val authorTag: String? = null,
    /** 호출자의 계정이 쓴 글이다 (프로필과 무관 · 익명은 항상 false) — 수정 · 삭제 단추는 이것으로 판단한다 */
    val mine: Boolean,
    /** 비회원이 쓴 글이다 — 이름은 `authorName` 이고, 화면은 「(비회원)」 으로 회원 닉네임 사칭을 가른다 */
    val authorGuest: Boolean = false,
)

data class PostDetailResponse(
    val id: Long,
    val boardCode: String,
    /** 공개 작성자 id — `author-profile` 이 있는 앱에서는 프로필 id, 없으면 계정 id. 아직 넘겨받지 않은 옛 행은 `pending:…` */
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
    /** 작성자의 계정이나 프로필이 지워졌다 — 화면은 이름 대신 "삭제된 사용자" 를 보인다 */
    val authorDeleted: Boolean,
    /** 닉네임 — 계정 모듈(또는 앱의 AuthorDirectory)이 모르거나 지워진 작성자면 null. 화면은 이름이 없으면 authorDeleted · authorId 로 대신한다 */
    val authorName: String? = null,
    /** 같은 닉네임을 구분하는 4자리 꼬리표 (없으면 null) */
    val authorTag: String? = null,
    /** 호출자의 계정이 쓴 글이다 (프로필과 무관 · 익명은 항상 false) — 수정 · 삭제 단추는 이것으로 판단한다 */
    val mine: Boolean,
    /** 비회원이 쓴 글이다 — 이름은 `authorName` 이고, 화면은 「(비회원)」 으로 회원 닉네임 사칭을 가른다 */
    val authorGuest: Boolean = false,
    /** 호출자가 이 글의 댓글을 승인 · 가릴 수 있다 (운영자, 댓글 관리가 켜진 게시판의 글 주인) — 화면은 단추를 이것으로 그린다 */
    val canManageComments: Boolean = false,
    /** 이 글의 댓글 승인 규칙 — null 이면 게시판 값 */
    val commentApproval: ApprovalMode? = null,
)

/** 목록의 한 줄이면 replies 가 있다 (최상위: 모든 자손 작성 순 평평하게, 답글: 빈 배열). 한 댓글만 돌려주는 응답에는 replies 가 없다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CommentResponse(
    val id: Long,
    val postId: Long,
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val parentId: Long?,
    val rootId: Long,
    val depth: Int,
    /** 공개 작성자 id — `author-profile` 이 있는 앱에서는 프로필 id, 없으면 계정 id. 아직 넘겨받지 않은 옛 행은 `pending:…` */
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
    /** 작성자의 계정이나 프로필이 지워졌다 — 화면은 이름 대신 "삭제된 사용자" 를 보인다 */
    val authorDeleted: Boolean,
    /** 닉네임 — 계정 모듈(또는 앱의 AuthorDirectory)이 모르거나 지워진 작성자면 null. 화면은 이름이 없으면 authorDeleted · authorId 로 대신한다 */
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val authorName: String? = null,
    /** 같은 닉네임을 구분하는 4자리 꼬리표 (없으면 null) */
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val authorTag: String? = null,
    /** 호출자의 계정이 쓴 댓글이다 (프로필과 무관 · 익명은 항상 false) */
    val mine: Boolean,
    /** 비회원이 쓴 댓글이다 — 이름은 `authorName` */
    val authorGuest: Boolean = false,
)

data class ReactionStateResponse(val counts: Map<String, Long>, val myReactions: Set<String>)

data class CreateBoardRequest(val code: String? = null, val name: String? = null, val description: String? = null)

data class CreatePostRequest(
    val title: String? = null,
    val body: String? = null,
    val attachments: List<String>? = null,
    val status: PostStatus? = null,
    /** 작성 프로필 id — 작성자 신원 빈이 있는 앱에서만 (필수), 없는 앱에서 보내면 403 */
    val authorId: String? = null,
    /** 비회원의 이름 — 로그인하지 않은 쓰기에서만 (필수). 로그인한 사람이 보내면 400. 비밀값은 헤더 `X-Board-Guest-Secret` */
    val guestName: String? = null,
    /** 비회원의 캡차 토큰 — `captchaRequired` 일 때. 멱등 지문에는 넣지 않는다 */
    val captchaToken: String? = null,
    /** 이 글의 댓글 승인 규칙 — 댓글 관리가 켜진 게시판에서만 (아니면 400) */
    val commentApproval: ApprovalMode? = null,
)

data class UpdatePostRequest(
    val title: String? = null,
    val body: String? = null,
    val attachments: List<String>? = null,
    val status: PostStatus? = null,
    /** 이 글의 댓글 승인 규칙 — 댓글 관리가 켜진 게시판에서만 */
    val commentApproval: ApprovalMode? = null,
)

data class UpdateBoardRequest(val name: String? = null, val description: String? = null, val settings: UpdateBoardSettingsRequest? = null)

data class UpdateBoardSettingsRequest(
    val guestWrite: GuestWrite? = null,
    val postApproval: ApprovalMode? = null,
    val commentApproval: ApprovalMode? = null,
    val ownerModeratesComments: Boolean? = null,
)

data class PostModerationRequest(val status: PostStatus? = null, val pinned: Boolean? = null)

data class CreateCommentRequest(
    val body: String? = null,
    val parentId: Long? = null,
    val authorId: String? = null,
    /** 비회원의 이름 (로그인하지 않은 쓰기에서만 필수) — 비밀값은 헤더 `X-Board-Guest-Secret` */
    val guestName: String? = null,
    val captchaToken: String? = null,
)

data class UpdateCommentRequest(val body: String? = null)

data class CommentModerationRequest(val status: CommentStatus? = null)

data class ReactionRequest(val type: String? = null)

internal fun BoardConfigView.toResponse() =
    BoardConfigResponse(
        reactionTypes, reactionMode, maxCommentDepth, titleMaxLength, bodyMaxLength, commentMaxLength, maxPageSize, canModerate, authorRequired,
        guestEnabled, guestSecret, guestNameMaxLength, captchaRequired,
    )

internal fun Board.toResponse() =
    BoardResponse(code, name, description, postCount, createdAt, BoardSettingsResponse(settings.guestWrite, settings.postApproval, settings.commentApproval, settings.ownerModeratesComments))

internal fun PostSummaryView.toResponse() = with(item) {
    PostSummaryResponse(
        id, boardCode, publicAuthorId, title, excerpt, status, pinned, viewCount, commentCount,
        reactions.counts, reactions.myReactions, attachmentCount, createdAt, updatedAt,
        authorDeleted, authorName = author?.name, authorTag = author?.tag, mine = mine, authorGuest = BoardAuthors.isGuest(authorId),
    )
}

internal fun PostDetailView.toResponse(excerptLength: Int) = with(post) {
    PostDetailResponse(
        id, boardCode, publicAuthorId, title, body.take(excerptLength), body, attachments, status, pinned, viewCount, commentCount,
        reactions.counts, reactions.myReactions, attachments.size, createdAt, updatedAt,
        authorDeleted, authorName = author?.name, authorTag = author?.tag, mine = mine, authorGuest = BoardAuthors.isGuest(authorId),
        canManageComments = canManageComments, commentApproval = commentApproval,
    )
}

/** [thread] 이면 목록의 한 줄 — `replies` 를 항상 낸다 (최상위는 자손 전부, 답글은 `[]`). 아니면 한 댓글만 돌려주는 응답이라 `replies` 가 없다. */
internal fun CommentView.toResponse(thread: Boolean = false): CommentResponse = with(comment) {
    CommentResponse(
        id, postId, parentId, rootId, depth, publicAuthorId, this@toResponse.body, status,
        reactions.counts, reactions.myReactions, replyCount, createdAt, updatedAt,
        replies = if (thread) replies.map { it.toResponse(thread = true) } else null,
        authorDeleted = authorDeleted, authorName = author?.name, authorTag = author?.tag, mine = mine, authorGuest = BoardAuthors.isGuest(authorId),
    )
}

internal fun ReactionState.toResponse() = ReactionStateResponse(counts, myReactions)
