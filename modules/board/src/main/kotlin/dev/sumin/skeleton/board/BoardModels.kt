package dev.sumin.skeleton.board

import java.time.Instant

/**
 * 글 상태. 지우기는 행을 남기는 소프트 삭제다 (DELETED). DRAFT 는 작성자 · 운영자만 본다.
 * PENDING 은 승인을 기다리는 새 글 — 운영자 · 회원 작성자만 본다 (승인 거절 = HIDDEN, 새 상태는 없다).
 */
enum class PostStatus { DRAFT, PENDING, PUBLISHED, HIDDEN, DELETED }

/**
 * 댓글 상태. PENDING · PUBLISHED 가 아니면 본문은 모두에게 숨기고 스레드 모양만 남긴다.
 * PENDING(승인 대기)은 운영자 · 관리하는 글 주인 · 회원 작성자에게만 보이고, 그 밖의 사람에게는 스레드에 자리도 없다.
 */
enum class CommentStatus { PENDING, PUBLISHED, HIDDEN, DELETED }

/** 비회원 쓰기 허용 범위 — 게시판 설정 (기본 NONE) */
enum class GuestWrite { NONE, COMMENTS, ALL }

/** 승인을 거쳐야 하는 작성자 — OFF 거치지 않음 · GUESTS 비회원만 · ALL 운영자 외 모두 */
enum class ApprovalMode { OFF, GUESTS, ALL }

/** 승인 규칙이 걸린 대상 */
enum class ApprovalTarget { POST, COMMENT }

/** 마지막으로 상태를 바꾼 쪽 — 글 주인이 운영자가 가린 댓글을 되살리지 못하게 한다 */
enum class ModeratedBy { OWNER, MODERATOR }

/** 고친 판의 대상 종류 */
enum class RevisionTarget { POST, COMMENT }

enum class RevisionDecision { APPROVE, REJECT }

enum class RevisionOutcome { APPROVED, REJECTED, DISCARDED }

/** 공개본 위에 대기 중인 고친 내용 — 대상마다 하나. [title] 은 글만 */
data class Revision(
    val target: RevisionTarget,
    val targetId: Long,
    val boardCode: String,
    val postId: Long,
    val title: String?,
    val body: String,
    val attachments: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 판의 버전 비교 — DB 시각의 정밀도(마이크로초)로 맞춘다 */
fun Revision.sameVersion(other: Instant): Boolean =
    updatedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS) == other.truncatedTo(java.time.temporal.ChronoUnit.MICROS)

/** 게시판 설정 — 새 기능은 모두 꺼진 채 시작한다 */
data class BoardSettings(
    val guestWrite: GuestWrite = GuestWrite.NONE,
    val postApproval: ApprovalMode = ApprovalMode.OFF,
    val commentApproval: ApprovalMode = ApprovalMode.OFF,
    /** 글 주인이 자기 글의 댓글을 승인 · 가린다 */
    val ownerModeratesComments: Boolean = false,
)

enum class ReactionTarget { POST, COMMENT }

/**
 * SINGLE: 대상 하나에 호출자는 반응 하나만 (다른 종류를 누르면 바뀐다 — 좋아요/싫어요).
 * PER_TYPE: 종류마다 하나씩, 여러 종류를 함께 (공감 + 좋아요).
 * 같은 테이블 · 같은 유니크 키로 둘 다 돌아가므로 스키마 변경 없이 바꾼다.
 */
enum class ReactionMode { SINGLE, PER_TYPE }

enum class PostSort { LATEST, REACTIONS, COMMENTS }

enum class CommentSort { OLDEST, LATEST, REACTIONS }

/** 호출자 — 계정 id 와 운영자 여부. 익명은 null 로 다룬다. */
data class BoardCaller(val accountId: String, val moderator: Boolean = false)

data class Board(
    val code: String,
    val name: String,
    val description: String?,
    val postCount: Long = 0,
    val createdAt: Instant,
    val settings: BoardSettings = BoardSettings(),
)

data class Post(
    val id: Long,
    val boardCode: String,
    val authorId: String,
    val title: String,
    val body: String,
    val status: PostStatus,
    val pinned: Boolean,
    val viewCount: Long,
    val commentCount: Long,
    val reactionCount: Long,
    val attachments: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 공개 작성자 id. null = 이 기능 이전의 행 (공개 id = authorId) */
    val authorRef: String? = null,
    /** 비회원 글의 이름 (author_id 가 guest: 로 시작). 비밀 해시는 모델에 싣지 않는다 — [PostRepository.guestSecretHash] */
    val guestName: String? = null,
    /** 이 글의 댓글 승인 규칙 — null 이면 게시판 값 */
    val commentApproval: ApprovalMode? = null,
    val moderatedBy: ModeratedBy? = null,
)

/** 목록용 — 본문 대신 앞부분(excerpt)과 첨부 개수만. */
data class PostListItem(
    val id: Long,
    val boardCode: String,
    val authorId: String,
    val title: String,
    val excerpt: String,
    val status: PostStatus,
    val pinned: Boolean,
    val viewCount: Long,
    val commentCount: Long,
    val reactionCount: Long,
    val attachmentCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 공개 작성자 id. null = 이 기능 이전의 행 (공개 id = authorId) */
    val authorRef: String? = null,
    val guestName: String? = null,
)

data class Comment(
    val id: Long,
    val postId: Long,
    val parentId: Long?,
    /** 최상위 댓글은 자기 id */
    val rootId: Long,
    val depth: Int,
    val authorId: String,
    val body: String,
    val status: CommentStatus,
    val reactionCount: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 공개 작성자 id. null = 이 기능 이전의 행 (공개 id = authorId) */
    val authorRef: String? = null,
    val guestName: String? = null,
    val moderatedBy: ModeratedBy? = null,
    /** 한 번이라도 공개된 적 있는가. false(승인 대기 → 거절 · 대기 중 삭제)인 행은 관리하는 쪽과 쓴 회원 말고는 스레드에 자리도 안 보인다. 이 열을 모르는 자기 저장소는 true 로 읽힌다(옛 동작) */
    val everPublished: Boolean = true,
)

data class ReactionCounts(val counts: Map<String, Long>)

data class ReactionState(val counts: Map<String, Long>, val myReactions: Set<String>)

data class PageResult<T>(val values: List<T>, val totalElements: Long)

data class NewPost(
    val boardCode: String,
    val authorId: String,
    val title: String,
    val body: String,
    val status: PostStatus,
    val attachments: List<String>,
    val now: Instant,
    /** 공개 작성자 id. null = 이 기능 이전의 행 (공개 id = authorId) */
    val authorRef: String? = null,
    val guestName: String? = null,
    /** 저장할 비밀 해시 — 모델 · 로그에 남지 않게 이 입력에만 있다 */
    val guestSecretHash: String? = null,
    val commentApproval: ApprovalMode? = null,
) {
    override fun toString() = "NewPost(boardCode=$boardCode, status=$status)"
}

/** null 인 칸은 바꾸지 않는다. */
data class PostChange(
    val title: String? = null,
    val body: String? = null,
    val attachments: List<String>? = null,
    val status: PostStatus? = null,
    val pinned: Boolean? = null,
    val commentApproval: ApprovalMode? = null,
    /** 상태를 바꾼 쪽 — 상태가 바뀔 때만 의미가 있다 */
    val moderatedBy: ModeratedBy? = null,
)

data class NewComment(
    val postId: Long,
    val parentId: Long?,
    val rootId: Long?,
    val depth: Int,
    val authorId: String,
    val body: String,
    val now: Instant,
    /** 공개 작성자 id. null = 이 기능 이전의 행 (공개 id = authorId) */
    val authorRef: String? = null,
    /** PUBLISHED 가 아니면(PENDING) 글의 comment_count 를 올리지 않는다 */
    val status: CommentStatus = CommentStatus.PUBLISHED,
    val guestName: String? = null,
    val guestSecretHash: String? = null,
    val attachments: List<String> = emptyList(),
) {
    override fun toString() = "NewComment(postId=$postId, status=$status)"
}

data class PostQuery(
    val boardCode: String,
    val page: Int,
    val size: Int,
    val sort: PostSort,
    /** 지정하면 그 종류의 반응 수로 정렬한다 (sort 를 덮어쓴다) */
    val reactionType: String? = null,
    val search: String? = null,
    val statuses: Set<PostStatus> = setOf(PostStatus.PUBLISHED),
    val authorId: String? = null,
    val excerptLength: Int = 140,
)

/**
 * 최상위 댓글 한 페이지. PENDING 댓글은 [pendingFor] 가 허락한 사람에게만 — 그 밖에는 쿼리가 걸러 쪽 수 · 전체 수에도 들어가지 않는다.
 */
data class CommentRootQuery(
    val postId: Long,
    val page: Int,
    val size: Int,
    val sort: CommentSort,
    val pendingFor: PendingVisibility = PendingVisibility.NONE,
)

/** PENDING 댓글을 누구에게 보이나 — [all] 이면 전부(운영자 · 관리하는 글 주인), 아니면 author_id 가 [authorId] 인 것만(회원 작성자) */
data class PendingVisibility(val all: Boolean = false, val authorId: String? = null) {
    fun sees(comment: Comment): Boolean =
        (comment.status != CommentStatus.PENDING && comment.everPublished) || all || (authorId != null && authorId == comment.authorId)

    companion object {
        val NONE = PendingVisibility()
    }
}

data class BoardChange(val name: String? = null, val description: String? = null, val settings: BoardSettings? = null)
