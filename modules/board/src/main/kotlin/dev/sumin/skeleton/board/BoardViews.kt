package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorDirectory

/** 대기 중인 고친 내용 — 쓴 사람 · 운영자 · 관리하는 글 주인에게만 보인다 (남에게는 늘 null) */
data class RevisionView(val title: String?, val body: String, val attachments: List<String>, val submittedAt: java.time.Instant)

internal fun Revision.view() = RevisionView(title, body, attachments, updatedAt)

/** 서비스가 돌려주는 읽기 모양 — 컨트롤러가 DTO 로 바꾼다. 반응 개수는 설정된 종류 전부를 (0 포함) 담는다. */
data class PostSummaryView(
    val item: PostListItem,
    val reactions: ReactionState,
    /** 응답에 나가는 작성자 id ([BoardAuthors.publicId]) — 계정 id 가 아니다 */
    val publicAuthorId: String,
    /** author_id 가 톰스톤이거나 카드가 지워진 프로필이다 */
    val authorDeleted: Boolean,
    /** 호출자의 계정이 author_id 다 — 프로필 · 공개 id 와 무관하다 */
    val mine: Boolean,
    val author: AuthorCard? = null,
    /** 고친 글 목록(`revision=pending`)에서만 채워진다 */
    val pendingRevision: RevisionView? = null,
)

data class PostDetailView(
    val post: Post,
    val reactions: ReactionState,
    val publicAuthorId: String,
    val authorDeleted: Boolean,
    val mine: Boolean,
    val author: AuthorCard? = null,
    /** 호출자가 이 글의 댓글을 승인 · 가릴 수 있다 — 화면은 승인 · 가리기 단추를 이것으로 그린다 */
    val canManageComments: Boolean = false,
    val pendingRevision: RevisionView? = null,
)

/** 최상위 댓글이면 [replies] 에 모든 자손이 작성 순으로 평평하게 들어 있다 (답글의 replies 는 항상 비어 있다). body 는 PUBLISHED 가 아니면 null. */
data class CommentView(
    val comment: Comment,
    /** 응답에 나가는 작성자 id ([BoardAuthors.publicId]) */
    val publicAuthorId: String,
    val authorDeleted: Boolean,
    val mine: Boolean,
    val body: String?,
    val reactions: ReactionState,
    val replyCount: Int,
    val replies: List<CommentView> = emptyList(),
    /** 작성자 이름 · 꼬리표 — [AuthorDirectory] 가 모르거나 지워진 작성자면 null */
    val author: AuthorCard? = null,
    val pendingRevision: RevisionView? = null,
    /** 보이는 댓글(PUBLISHED, 또는 보는 사람에게 남아 있는 PENDING)의 첨부 키 — 가린 · 지운 댓글은 비어 있다 */
    val attachments: List<String> = emptyList(),
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
    /** [dev.sumin.skeleton.common.author.AuthorIdentities] 빈이 있다 — 글 · 댓글을 쓸 때 `authorId` 가 필수 */
    val authorRequired: Boolean,
    /** 비회원 쓰기가 켜져 있다 (`skeleton.board.guest.enabled`) — 게시판마다 `settings.guestWrite` 가 실제로 정한다 */
    val guestEnabled: Boolean = false,
    val guestSecret: GuestSecretMode = GuestSecretMode.PASSWORD,
    val guestNameMaxLength: Int = 20,
    /** 비회원의 만들기에 `captchaToken` 이 필요하다 */
    val captchaRequired: Boolean = false,
    /** 댓글 하나의 첨부 수 상한 — 0 이면 댓글 첨부가 꺼져 있다 */
    val commentMaxAttachments: Int = 0,
)

data class CreatePost(
    val title: String?,
    val body: String?,
    val attachments: List<String>?,
    val status: PostStatus?,
    val authorId: String? = null,
    /** 비회원의 이름 — 로그인한 사람이 보내면 400 */
    val guestName: String? = null,
    /** 이 글의 댓글 승인 규칙 — `owner_moderates_comments` 인 게시판만 받는다 (아니면 400). null 이면 게시판 값 */
    val commentApproval: ApprovalMode? = null,
)

data class UpdatePost(
    val title: String?,
    val body: String?,
    val attachments: List<String>?,
    val status: PostStatus?,
    val commentApproval: ApprovalMode? = null,
)

/** 운영자의 게시판 고치기 — null 인 칸은 그대로. [settings] 의 null 칸도 현재 값을 유지한다 */
data class UpdateBoard(val name: String? = null, val description: String? = null, val settings: UpdateBoardSettings? = null)

data class UpdateBoardSettings(
    val guestWrite: GuestWrite? = null,
    val postApproval: ApprovalMode? = null,
    val commentApproval: ApprovalMode? = null,
    val ownerModeratesComments: Boolean? = null,
)

data class PostListRequest(
    val page: Int,
    val size: Int,
    val sort: PostSort = PostSort.LATEST,
    val q: String? = null,
    val reaction: String? = null,
    val status: PostStatus? = null,
    val mine: Boolean = false,
    /** true 면 대기 중인 고친 글 목록 (운영자) */
    val revision: Boolean = false,
)

/** 댓글이 달렸을 때 알림 받을 사람과 그 맥락. 보내는 쪽(notification 모듈)은 [BoardNotifier] 구현이 안다. */
data class BoardCommentNotice(
    val recipientId: String,
    val post: Post,
    val comment: Comment,
    val parent: Comment?,
    /** 댓글 작성자의 공개 id ([BoardAuthors.publicId]) — 받는 사람의 편지함에 남으므로 계정 id 를 싣지 않는다 */
    val publicAuthorId: String,
    val authorName: String? = null,
    /** 고친 내용이 승인 대기로 들어왔다는 알림이다 (type 은 같은 `comment.pending`, payload 에 `revision: true`) */
    val revision: Boolean = false,
)

fun interface BoardNotifier {
    /** 댓글이 **공개되었다** (바로 공개되었거나 승인되었다) — 글 작성자 · 부모 댓글 작성자에게 */
    fun commentCreated(notice: BoardCommentNotice)

    /** 승인 대기로 들어온 댓글 — 댓글을 관리하는 글 주인에게만. 기본은 보내지 않는다 */
    fun commentPending(notice: BoardCommentNotice) = Unit
}

object NoopBoardNotifier : BoardNotifier {
    override fun commentCreated(notice: BoardCommentNotice) = Unit
}
