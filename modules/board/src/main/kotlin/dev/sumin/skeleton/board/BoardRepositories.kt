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

    /**
     * 설정을 정해 만든다 (시드 · 운영자). 기본 구현은 설정이 기본값(새 기능 모두 꺼짐)일 때만 위의 [create] 로 이어지고, 아니면 **닫힌 쪽으로** 실패한다 —
     * 설정 열이 없는 자기 저장소가 설정을 조용히 버리지 않게. `board-jdbc` 가 구현한다.
     */
    fun create(code: String, name: String, description: String?, now: Instant, settings: BoardSettings): Boolean =
        if (settings == BoardSettings()) create(code, name, description, now)
        else throw UnsupportedOperationException("This BoardRepository does not store board settings - use board-jdbc or implement create(…, settings)")

    /** 이름 · 설명 · 설정을 고친다 (null 인 칸은 그대로). 없는 게시판이면 null. 기본 구현은 닫힌 쪽으로 실패한다 — `board-jdbc` 가 구현한다 */
    fun update(code: String, change: BoardChange): Board? =
        throw UnsupportedOperationException("This BoardRepository cannot update boards - use board-jdbc or implement update(code, change)")
}

interface PostRepository {
    fun insert(post: NewPost): Post

    /** 상태와 상관없이 (권한은 서비스가 본다). 첨부 포함. */
    fun find(id: Long): Post?

    fun update(id: Long, change: PostChange, now: Instant): Post?

    fun incrementViews(id: Long)

    fun page(query: PostQuery): PageResult<PostListItem>

    /**
     * 비회원 글의 비밀 해시 (비회원 행이 아니거나 글이 없으면 null). 해시는 모델에 싣지 않으므로 확인할 때만 읽는다.
     * 기본 구현은 null — 늘 불일치다. 비회원 쓰기를 모르는 자기 저장소는 **닫힌 쪽으로** 실패한다.
     */
    fun guestSecretHash(id: Long): String? = null
}

interface CommentRepository {
    /** 글의 comment_count 를 먼저 올리고(행 잠금) 삽입한다 — 같은 트랜잭션. 글이 없으면 null. */
    fun insert(comment: NewComment): Comment?

    fun find(id: Long): Comment?

    fun updateBody(id: Long, body: String, now: Instant): Comment?

    /** PUBLISHED 로 드나들면 글의 comment_count 가 같은 트랜잭션에서 ±1 된다. */
    fun setStatus(id: Long, status: CommentStatus, now: Instant): Comment?

    /**
     * 상태를 바꾸며 **바꾼 쪽**([moderatedBy])도 남긴다 — null 이면 [setStatus] 와 같다. 글 주인이 운영자가 가린 댓글을 되살리지 못하게 하는 규칙이 이 값을 읽는다.
     * 기본 구현은 [setStatus] 로 이어진다 (열이 없는 자기 저장소는 값을 남기지 못한다 — 글 주인 관리는 설정을 저장하는 `board-jdbc` 에서만 켜진다).
     */
    fun setStatus(id: Long, status: CommentStatus, now: Instant, moderatedBy: ModeratedBy?): Comment? = setStatus(id, status, now)

    /** 승인 대기 댓글 한 페이지 — 한 글의 PENDING 만, 오래된 순 평평하게 (관리자용). 기본 구현은 비어 있다 */
    fun pendingPage(postId: Long, page: Int, size: Int): PageResult<Comment> = PageResult(emptyList(), 0)

    /** 최상위 댓글 한 페이지. */
    fun rootPage(query: CommentRootQuery): PageResult<Comment>

    /** 주어진 최상위 댓글들의 모든 자손 — 쿼리 한 번 (root_id IN …), 작성 순. */
    fun descendants(rootIds: Collection<Long>): List<Comment>

    /** 댓글들의 첨부 키 — 쿼리 한 번 (id → 키들, 순서대로). 첨부를 저장하지 않는 자기 저장소는 비어 있다 */
    fun attachments(ids: Collection<Long>): Map<Long, List<String>> = emptyMap()

    /** 본문과(널이 아니면) 첨부를 함께 고친다. 기본 구현은 첨부를 못 고치는 저장소에서 **닫힌 쪽으로** 실패한다 */
    fun updateContent(id: Long, body: String, attachments: List<String>?, now: Instant): Comment? =
        if (attachments == null) updateBody(id, body, now)
        else throw UnsupportedOperationException("This CommentRepository does not store comment attachments - use board-jdbc or implement updateContent")

    /** 비회원 댓글의 비밀 해시 — [PostRepository.guestSecretHash] 와 같은 규칙 (기본 null = 늘 불일치) */
    fun guestSecretHash(id: Long): String? = null
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

/**
 * 계정이 지워질 때 그 계정의 글 · 댓글 작성자와 반응의 계정을 [tombstone] 으로 바꾼다 (행은 남는다 — 대화의 맥락 · 카운터를 지키려고).
 * 별도 포트라서 기존 저장소 구현은 영향이 없다. `board-jdbc` 가 구현하고, 앱이 자기 저장소를 둘 때는 이것도 구현하면 계정 삭제가 board 데이터까지 닿는다.
 */
interface BoardErasureRepository {
    /** 바꾼 행 수. **멱등** — 다시 불러도 같은 결과 (이미 바뀐 행은 대상이 아니다) */
    fun anonymizeAuthor(accountId: String, tombstone: String): Int
}

/**
 * 고친 판 저장소 포트 — `board-jdbc` 가 구현한다. 기본 구현은 판이 없는 것으로 읽고, **판을 만들려는 첫 수정에서 닫힌 쪽으로 실패한다**
 * (자기 저장소를 둔 앱이 이것 없이 승인을 켜면 "board-jdbc 를 두거나 구현하라" 오류).
 */
/** 판 만들기의 결과 — [TARGET_GONE] 은 잠근 뒤 보니 대상이 더 이상 PUBLISHED 가 아니다(가려지거나 지워졌다): 판을 넣지 않았다 */
enum class RevisionSubmit { CREATED, REPLACED, TARGET_GONE }

/** 판 승인 · 거절의 결과 — [CHANGED] 는 결정하려던 판이 그 사이 다른 판으로 바뀌었다(작성자가 덮어썼다): 아무것도 하지 않았다 */
enum class RevisionWrite { DONE, NONE, CHANGED }

/**
 * 고친 판 저장소 포트 — `board-jdbc` 가 구현한다. 기본 구현은 판이 없는 것으로 읽고, **판을 만들려는 첫 수정에서 닫힌 쪽으로 실패한다**
 * (자기 저장소를 둔 앱이 이것 없이 승인을 켜면 "board-jdbc 를 두거나 구현하라" 오류).
 * 판의 **버전**은 판의 `updatedAt` 이다 — 결정(승인 · 거절)은 운영자가 본 버전을 함께 보내고, 잠근 뒤에 같을 때만 한다(보는 사이 덮어쓴 판이 공개되지 않게).
 */
interface BoardRevisionRepository {
    /** 대상 행을 잠그고 **PUBLISHED 인지 다시 본 뒤** 옛 판을 지우고 넣는다 (upsert 문법이 방언마다 달라서) */
    fun submit(revision: Revision): RevisionSubmit =
        throw UnsupportedOperationException("This app has no BoardRevisionRepository - use board-jdbc or implement it to turn on approval for edits")

    fun find(target: RevisionTarget, ids: Collection<Long>): Map<Long, Revision> = emptyMap()

    /** 판만 지운다(버전 상관없이 — 직접 수정 · 버림용) — 있었으면 true */
    fun delete(target: RevisionTarget, id: Long): Boolean = false

    /**
     * 한 트랜잭션에서 대상을 잠그고 판의 제목 · 본문 · 첨부를 원래 행에 옮긴 뒤(`updated_at` 갱신) 판을 지운다. 판이 없거나 대상이 PUBLISHED 가 아니면
     * (그 경우 남은 판도 지운다) 아무것도 옮기지 않고 [RevisionWrite.NONE]. [expectedVersion] 이 지금 판의 버전과 다르면 [RevisionWrite.CHANGED].
     */
    fun apply(target: RevisionTarget, id: Long, now: Instant, expectedVersion: Instant): RevisionWrite = RevisionWrite.NONE

    /** 판을 거절(지움) — [apply] 와 같은 버전 확인 */
    fun reject(target: RevisionTarget, id: Long, expectedVersion: Instant): RevisionWrite {
        val current = find(target, listOf(id))[id] ?: return RevisionWrite.NONE
        if (!current.sameVersion(expectedVersion)) return RevisionWrite.CHANGED
        return if (delete(target, id)) RevisionWrite.DONE else RevisionWrite.NONE
    }

    /** 대기 중인 판 한 페이지 (오래된 것부터). 글: [boardCode] 의 모든 글 · 댓글: [postId] 의 모든 댓글 */
    fun pending(target: RevisionTarget, boardCode: String?, postId: Long?, page: Int, size: Int): PageResult<Revision> = PageResult(emptyList(), 0)
}

/** 판이 없는 기본 구현 — `board-jdbc` 가 없으면 이것이다 */
object NoBoardRevisions : BoardRevisionRepository

/** 실패한 시도의 다음 시도 시각 - 지수(1분, 2분, 4분 …)이고 최대 1시간 */
object PublicSyncBackoff {
    val MAX: java.time.Duration = java.time.Duration.ofHours(1)

    /** 지울 것이 남은 항목(공개 영역에 남으면 안 되는 쪽)의 상한 - 공개 쪽 보장이라 늦게 다시 하지 않는다 */
    val MAX_WHEN_REMOVING: java.time.Duration = java.time.Duration.ofMinutes(5)

    fun next(now: java.time.Instant, attempts: Int, max: java.time.Duration = MAX): java.time.Instant {
        val minutes = 1L shl (attempts - 1).coerceIn(0, 12)
        return now.plus(java.time.Duration.ofMinutes(minutes).coerceAtMost(max))
    }

    private fun java.time.Duration.coerceAtMost(max: java.time.Duration) = if (this > max) max else this
}

/** 조정 큐의 한 줄 - 대상과 그 줄의 번호 */
data class QueuedItem(val target: RevisionTarget, val id: Long, val version: Long)

/** 공개 영역에 복사해 둔 첨부 한 줄 - 어느 글 · 댓글의 어느 비공개 키인가 */
data class PublicCopy(val target: RevisionTarget, val targetId: Long, val key: String)

/**
 * 공개 영역의 복사본을 board 상태에 맞추는 조정자의 저장소 - `skeleton.storage.web.public` 이 켜져 있을 때만 쓰인다(없으면 조정자가 없다).
 * 「공개 조건」: 글은 PUBLISHED, 댓글은 PUBLISHED 이고 그 글도 PUBLISHED. 고친 판의 첨부는 어떤 경우에도 해당하지 않는다.
 */
interface BoardPublicAttachmentRepository {
    /** 지금 공개 조건에 드는 이 항목의 첨부 키 */
    fun eligible(target: RevisionTarget, id: Long): List<String>

    /** 공개 영역에 복사해 둔(복사했을 수 있는) 이 항목의 키 */
    fun copied(target: RevisionTarget, id: Long): List<String>

    /** 「복사했을 수 있다」를 먼저 적는다 - 복사가 실패해도 지우는 쪽이 찾을 수 있게 (멱등) */
    fun mark(copy: PublicCopy, now: java.time.Instant)

    fun unmark(copy: PublicCopy)

    /** 같은 비공개 키를 쥔 다른 항목의 줄이 남아 있다 - 있으면 공개 객체를 지우면 안 된다 */
    fun heldElsewhere(copy: PublicCopy): Boolean

    /** 이 글의 댓글 중 복사가 있는 것 - 글이 공개를 떠나면 그 댓글들의 복사도 정리한다 */
    fun commentsWithCopies(postId: Long): List<Long>

    /** 이 글의 댓글 중 첨부가 있는 것 - 글이 공개로 돌아오면 그 댓글들의 사진을 다시 복사해야 한다(복사가 없는 댓글도) */
    fun commentsWithAttachments(postId: Long): List<Long>

    /** 복사가 있는데 공개 조건에서 빠진 것(가림 · 삭제 · 판으로 빠짐 · 글 숨김 · 탈퇴 …) */
    fun stale(limit: Int): List<PublicCopy>

    /** 공개 조건에 드는데 복사가 없는 것 */
    fun missing(limit: Int): List<PublicCopy>

    /** 맞추다 실패한 항목을 적어 둔다 - 주기 작업이 이것만 다시 맞춘다 (이미 있으면 줄의 번호를 +1 한다) */
    fun enqueue(target: RevisionTarget, id: Long, now: java.time.Instant)

    /** 적힌 항목을 [limit] 개 잡는다 - 잡힌 줄은 [lease] 동안 다른 인스턴스가 잡지 않는다 (`for update skip locked`). 줄의 번호(version)를 함께 준다 */
    fun claimQueued(limit: Int, now: java.time.Instant, lease: java.time.Duration): List<QueuedItem>

    /**
     * 맞추기에 실패했다 - 줄이 없으면 만들고(번호는 그대로) 시도 횟수를 +1, 다음 시도를 [PublicSyncBackoff] 만큼 늦춘다. 새 시도 횟수를 돌려준다.
     * 새 쓰기가 줄을 다시 적으면([enqueue]의 쓰는 쪽) 횟수는 0 으로 돌아가고 바로 다시 시도된다.
     */
    fun recordFailure(target: RevisionTarget, id: Long, now: java.time.Instant, maxBackoff: java.time.Duration = PublicSyncBackoff.MAX): Int

    /** 공개 객체를 지웠으니 CDN 캐시를 비울 공개 키를 적는다(멱등) - 요청 스레드가 외부 CDN 을 기다리지 않는다 */
    fun enqueuePurge(publicKey: String, now: java.time.Instant)

    /** 비울 공개 키를 [limit] 개 잡는다 (임대 [lease], 늦춘 것은 때가 될 때까지 제외) */
    fun claimPurges(limit: Int, now: java.time.Instant, lease: java.time.Duration): List<String>

    /** 비웠다 - 줄을 지운다 */
    fun finishPurge(publicKey: String)

    /** 비우기에 실패했다 - 시도 횟수 +1, 다음 시도를 늦춘다. 새 횟수 */
    fun failPurge(publicKey: String, now: java.time.Instant): Int

    /** 이 항목의 줄의 번호 - 줄이 없으면 null. 맞추기 **시작할 때** 읽는다 */
    fun queueVersion(target: RevisionTarget, id: Long): Long?

    /** 맞췄으면 줄을 지운다 - 시작할 때 읽은 [version] 과 **같을 때만**. 그 뒤 쓰기가 줄을 다시 적었으면(번호가 올랐으면) 줄이 남아 다음 조정이 맞춘다 */
    fun dequeue(target: RevisionTarget, id: Long, version: Long)

    /** [name] 작업을 지금 이 인스턴스가 해도 되는가 - 마지막 실행이 [minInterval] 보다 오래됐을 때만 한 인스턴스에게 참을 준다 (원자적 비교-교체) */
    fun tryStartRun(name: String, now: java.time.Instant, minInterval: java.time.Duration): Boolean

    /** 모든 줄을 한 쪽씩 - [after] 는 앞 쪽의 마지막 줄 (null 이면 처음). 전체 대조가 「줄이 가리키는 키」의 집합을 만든다 */
    fun copiesPage(after: PublicCopy?, limit: Int): List<PublicCopy>
}
