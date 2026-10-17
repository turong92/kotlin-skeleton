package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorIdentities

/**
 * 공개 작성자 id 규칙 — **한 곳**. 응답 DTO · 알림 payload · [AuthorNames] 조회가 모두 이것을 쓴다.
 *
 * ```
 * 공개 id = author_id                  (비회원 행 `guest:<ULID>` — 맨 앞 갈래. 행마다 새 값이라 계정과도 무관하다)
 *           ?: author_ref
 *           ?: author_id                 (AuthorIdentities 없음)
 *           ?: "pending:<행 종류><행 id>"   (AuthorIdentities 있음 + 아직 넘겨받지 않은 행 — 계정 id · 톰스톤을 내지 않는다)
 * ```
 *
 * [AuthorIdentities] 가 있으면 `author_id`(계정 id 또는 톰스톤)는 어떤 응답에도 나가지 않는다. `authorDeleted` 는 `author_id` 에서 따로 계산한다.
 */
object BoardAuthors {
    /** 지워진 계정의 작성자 값은 이 접두사로 시작한다 (계정 모듈의 `AccountTombstone` 과 같은 약속 — 모듈끼리 서로의 타입을 모르게 문자열 규칙으로 맞춘다) */
    const val DELETED_PREFIX = "deleted:"

    /** 아직 프로필로 넘겨받지 않은 행의 공개 id 접두사 — `pending:p123`(글 123) · `pending:c45`(댓글 45). 이름을 묻지 않는다 */
    const val PENDING_PREFIX = "pending:"

    /** 비회원 행의 `author_id` 는 `guest:<ULID>` — 행마다 새 값이라 계정 id(`acc_…`)와 겹치지 않고 같은 비회원의 두 글도 이어지지 않는다 */
    const val GUEST_PREFIX = "guest:"

    const val POST_ROW = 'p'
    const val COMMENT_ROW = 'c'

    fun isDeleted(authorId: String?): Boolean = authorId != null && authorId.startsWith(DELETED_PREFIX)

    fun isGuest(authorId: String?): Boolean = authorId != null && authorId.startsWith(GUEST_PREFIX)

    fun isPending(publicId: String?): Boolean = publicId != null && publicId.startsWith(PENDING_PREFIX)

    /** [identitiesPresent] = [AuthorIdentities] 빈이 있는가. [rowKind] 는 [POST_ROW] · [COMMENT_ROW] */
    fun publicId(authorRef: String?, authorId: String, rowKind: Char, rowId: Long, identitiesPresent: Boolean): String = when {
        isGuest(authorId) -> authorId
        authorRef != null -> authorRef
        !identitiesPresent -> authorId
        else -> "$PENDING_PREFIX$rowKind$rowId"
    }
}

/**
 * 작성자 신원 고리([AuthorIdentities])와 만나는 자리 — 쓰기 규칙(스펙 §7.1)과 첨부 막기(§7.8).
 * 고리는 부를 때마다 찾는다([identities]) — 앱이 빈을 두면 켜지고 없으면 꺼진다 ([AuthorNames] 와 같은 방식). board 는 `author-profile` 도 `account` 도 모른다.
 */
class BoardAuthorRules(private val identities: () -> AuthorIdentities?) {
    /** 고리가 있다 — 쓰기에 `authorId` 가 필수이고 응답의 공개 id 가 계정 id 가 아니다 */
    val active: Boolean get() = identities() != null

    /**
     * 쓰기에 실을 `author_ref` 를 정한다. 빈 없음+없음 → null(이 기능 이전과 같다) · 없음+있음 → [BoardErrorCode.AUTHOR_NOT_ALLOWED] ·
     * 있음+없음 → [BoardErrorCode.AUTHOR_REQUIRED] · 있음+있음 → 고리가 참이면 그 id, 거짓이면 [BoardErrorCode.AUTHOR_NOT_ALLOWED] (이유를 구별하지 않는 같은 문구).
     */
    fun authorRefFor(caller: BoardCaller, authorId: String?, boardCode: String): String? {
        val ref = authorId?.trim()?.takeIf { it.isNotEmpty() }
        val gate = identities()
        if (gate == null) {
            if (ref == null) return null
            throw notAllowed()
        }
        if (ref == null) throw BoardException(BoardErrorCode.AUTHOR_REQUIRED, "authorId is required")
        if (ref.length > MAX_REF_LENGTH || !gate.allows(caller.accountId, ref, AuthorContext(AuthorNames.SOURCE, boardCode))) throw notAllowed()
        return ref
    }

    /** 고리가 있을 때 첨부 키에 호출자 계정 id 가 들어 있으면 거절 — 키로 두 프로필의 글이 이어진다. storage 를 모른 채 문자열 포함만 본다 */
    fun rejectAccountIdKeys(caller: BoardCaller, keys: List<String>) {
        if (keys.isEmpty() || identities() == null) return
        if (keys.any { it.contains(caller.accountId) }) {
            throw BoardException(BoardErrorCode.CONTENT_INVALID, "attachment keys must not contain the account id")
        }
    }

    private fun notAllowed() = BoardException(BoardErrorCode.AUTHOR_NOT_ALLOWED, "Not allowed to write as this author")

    private companion object {
        /** `author_ref` 열 길이 (varchar(64)) */
        const val MAX_REF_LENGTH = 64
    }
}
