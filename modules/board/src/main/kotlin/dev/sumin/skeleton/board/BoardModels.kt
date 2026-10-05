package dev.sumin.skeleton.board

import java.time.Instant

/** 글 상태. 지우기는 행을 남기는 소프트 삭제다 (DELETED). DRAFT 는 작성자 · 운영자만 본다. */
enum class PostStatus { DRAFT, PUBLISHED, HIDDEN, DELETED }

/** 댓글 상태. PUBLISHED 가 아니면 본문은 모두에게 숨기고 스레드 모양만 남긴다. */
enum class CommentStatus { PUBLISHED, HIDDEN, DELETED }

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
)

/** null 인 칸은 바꾸지 않는다. */
data class PostChange(
    val title: String? = null,
    val body: String? = null,
    val attachments: List<String>? = null,
    val status: PostStatus? = null,
    val pinned: Boolean? = null,
)

data class NewComment(
    val postId: Long,
    val parentId: Long?,
    val rootId: Long?,
    val depth: Int,
    val authorId: String,
    val body: String,
    val now: Instant,
)

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

data class CommentRootQuery(val postId: Long, val page: Int, val size: Int, val sort: CommentSort)
