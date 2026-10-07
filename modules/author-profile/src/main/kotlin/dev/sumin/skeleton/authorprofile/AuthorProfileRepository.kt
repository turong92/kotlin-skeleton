package dev.sumin.skeleton.authorprofile

import java.time.Instant

/**
 * 프로필 저장소 포트 — 구현은 `author-profile-jdbc`(PostgreSQL · MySQL)가 한다. 이 모듈에는 메모리 구현이 없다 (재시작에 신원이 사라지면 안 된다 — `board` 와 같은 이유).
 * 구현이 지킬 약속:
 *  - **유니크는 DB 가 가른다** — 먼저 읽고 넣는 방식으로 판정하지 않는다. (`name_key`, `tag`) 는 둘 다 NULL 이 아닐 때만 겹침이다(NULL 은 서로 겹치지 않는다),
 *    `primary_of` 는 계정당 하나다. [insert] 는 둘 중 어느 쪽이 겹쳤는지 알리지 않고 false 를 돌려준다(부르는 쪽이 [findPrimary] 로 가른다).
 *  - 쓰기는 **주인 계정과 상태**를 조건에 건다 — 남의 · 없는 · 지워진 프로필은 같은 결과(false · [RenameResult.NOT_FOUND])다. 어느 쪽인지 구별해 알리지 않는다.
 *  - 이 모듈은 계정 id 와 프로필 id 쌍을 로그에 남기지 않는다 — 구현도 쌍을 평소 로그(INFO 이하)에 찍지 않는다.
 */
interface AuthorProfileRepository {
    /** 상태와 상관없이 id 로 한 줄 */
    fun find(id: String): AuthorProfile?

    /** 여러 id → 프로필 (지워진 것 포함) — **쿼리 한 번**(`where id in (…)`, 아주 많으면 덩어리로). 없는 id 는 결과에 없다 */
    fun findAll(ids: Collection<String>): Map<String, AuthorProfile>

    /** 계정의 살아 있는(ACTIVE) 프로필, 만든 순(같으면 id 순) */
    fun findActive(accountId: String): List<AuthorProfile>

    fun countActive(accountId: String): Int

    /** 계정의 첫 프로필([AuthorProfile.primaryOf] = 계정 id) — 지워졌어도 돌려준다. 없으면 null */
    fun findPrimary(accountId: String): AuthorProfile?

    /** 비교용 [key] 가 살아 있는 프로필에서 쓰고 있는 꼬리표들 — TAGGED 가 무작위 시도로 못 찾았을 때의 마지막 길 */
    fun tagsOf(key: String): Set<String>

    /**
     * 한 줄을 넣는다. (키, 꼬리표) 나 `primary_of` 가 이미 있으면 **유니크 위반으로** false (아무것도 바뀌지 않는다, insert-ignore) — 어느 쪽인지는 알리지 않는다.
     * 첫 프로필(`primary_of` 가 있는 줄)을 동시에 넣어도 한 쪽만 true 다.
     */
    fun insert(profile: AuthorProfile): Boolean

    /**
     * 살아 있는 [accountId] 의 프로필 [id] 의 이름 · 키 · 꼬리표를 **함께**(한 문장) 바꾼다. [tag] 가 null 이면 비운다(NONE).
     * (키, 꼬리표) 가 겹치면 [RenameResult.TAKEN] — 아무것도 바뀌지 않는다. 남의 · 없는 · 지워진 프로필은 [RenameResult.NOT_FOUND].
     */
    fun rename(id: String, accountId: String, name: String, key: String, tag: String?, now: Instant): RenameResult

    /**
     * 살아 있는 [accountId] 의 프로필 [id] 를 지운 표시로 바꾼다: `status=DELETED`, 이름 · 키 · 꼬리표 NULL(꼬리표가 풀린다), `deleted_at` = [now]. `primary_of` 는 그대로다.
     * 바꿨으면 true, 남의 · 없는 · 이미 지워진 프로필이면 false. 글 · 댓글 쪽 행은 건드리지 않는다.
     */
    fun markDeleted(id: String, accountId: String, now: Instant): Boolean

    /** 계정의 첫 프로필이 옛 글을 넘겨받았다고 표시한다 — `adopted_at` 이 비어 있을 때만 [now] 로 채운다(이미 있으면 그대로, false) */
    fun markAdopted(accountId: String, now: Instant): Boolean

    /** 탈퇴 — 계정의 프로필 행을 **모두** 지운다(지워진 것 포함: 형제 관계가 DB 에도 남지 않는다). 지운 줄 수. 멱등 */
    fun deleteByAccount(accountId: String): Int
}

enum class RenameResult { DONE, TAKEN, NOT_FOUND }
