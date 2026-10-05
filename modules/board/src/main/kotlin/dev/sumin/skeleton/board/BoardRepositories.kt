package dev.sumin.skeleton.board

import java.time.Instant

/**
 * 저장소 포트 — 구현은 `board-jdbc`(PostgreSQL · MySQL)가 한다. 이 모듈에는 메모리 구현이 없다.
 * 카운터 (comment_count · reaction_count · view_count)는 구현이 한 트랜잭션 안에서 원자적으로 맞춘다 (docs/modules/board.md).
 */
interface BoardRepository {
    fun findAll(): List<Board>

    fun find(code: String): Board?

    /** 이미 있으면 false (같은 코드 · 동시 생성에도 한 쪽만 true). */
    fun create(code: String, name: String, description: String?, now: Instant): Boolean
}

interface PostRepository {
    fun insert(post: NewPost): Post

    /** 상태와 상관없이 (권한은 서비스가 본다). 첨부 포함. */
    fun find(id: Long): Post?

    fun update(id: Long, change: PostChange, now: Instant): Post?

    fun incrementViews(id: Long)

    fun page(query: PostQuery): PageResult<PostListItem>
}

interface CommentRepository {
    /** 글의 comment_count 를 먼저 올리고(행 잠금) 삽입한다 — 같은 트랜잭션. 글이 없으면 null. */
    fun insert(comment: NewComment): Comment?

    fun find(id: Long): Comment?

    fun updateBody(id: Long, body: String, now: Instant): Comment?

    /** PUBLISHED 로 드나들면 글의 comment_count 가 같은 트랜잭션에서 ±1 된다. */
    fun setStatus(id: Long, status: CommentStatus, now: Instant): Comment?

    /** 최상위 댓글 한 페이지. */
    fun rootPage(query: CommentRootQuery): PageResult<Comment>

    /** 주어진 최상위 댓글들의 모든 자손 — 쿼리 한 번 (root_id IN …), 작성 순. */
    fun descendants(rootIds: Collection<Long>): List<Comment>
}

interface ReactionRepository {
    /**
     * 대상 행을 잠그고(모든 반응 변경이 같은 순서로 잠근다) 종류를 더한다. SINGLE 이면 같은 사람의 다른 종류를 지운다.
     * 대상이 없으면 false.
     */
    fun react(target: ReactionTarget, targetId: Long, accountId: String, type: String, mode: ReactionMode, now: Instant): Boolean

    /** type 이 null 이면 그 사람의 반응을 모두 지운다. 대상이 없으면 false. */
    fun remove(target: ReactionTarget, targetId: Long, accountId: String, type: String?): Boolean

    /** 쿼리 한 번 — 대상 id → (종류 → 개수) */
    fun counts(target: ReactionTarget, targetIds: Collection<Long>): Map<Long, Map<String, Long>>

    /** 쿼리 한 번 — 대상 id → 내가 누른 종류들 */
    fun mine(target: ReactionTarget, targetIds: Collection<Long>, accountId: String): Map<Long, Set<String>>
}
