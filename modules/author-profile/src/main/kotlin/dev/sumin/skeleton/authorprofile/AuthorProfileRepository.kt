package dev.sumin.skeleton.authorprofile

import java.time.Instant

/**
 * 프로필 저장소 포트 — 구현은 `author-profile-jdbc`(PostgreSQL · MySQL)가 한다. 이 모듈에는 메모리 구현이 없다 (재시작에 신원이 사라지면 안 된다 — `board` 와 같은 이유).
 * 구현이 지킬 약속:
 *  - **유니크는 DB 가 가른다** — 먼저 읽고 넣는 방식으로 판정하지 않는다. (`name_key`, `tag`) 는 둘 다 NULL 이 아닐 때만 겹침이다(NULL 은 서로 겹치지 않는다),
 *    `primary_of` 는 계정당 하나다. [insert] 는 둘 중 어느 쪽이 겹쳤는지 알리지 않고 false 를 돌려준다(부르는 쪽이 [findPrimary] 로 가른다).
 *  - 쓰기는 **주인 계정과 상태**를 조건에 건다 — 남의 · 없는 · 지워진 프로필은 같은 결과(false · [RenameResult.NOT_FOUND])다. 어느 쪽인지 구별해 알리지 않는다.
 *  - [find] · [findAll] 은 요청한 id 와 **정확히 같은** id 의 줄만 돌려준다 (대소문자 · 악센트만 다른 id 는 없는 id). 정렬이 id 를 느슨하게 비교하는 DB(MySQL 기본)에서도 변형 id 로 남의 줄에 닿지 못하게.
 *  - **트랜잭션**: [insertWithinCap] 은 바깥 트랜잭션에 합류해도 맞다(개수를 잠금 읽기로 센다). [rename] 은 바깥 트랜잭션 없이 불러야 한다 — 이름이 겹치면(`TAKEN`) PostgreSQL 은 그 트랜잭션 전체를 abort 시킨다.
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

    /** 비교용 [key] 를 이름으로 가진 살아 있는 **첫 프로필**들(`primary_of` 가 있는 줄) — 쿼리 한 번. 꼬리표 회피에서 이미 넘겨받은 계정을 가려내는 데 쓴다 */
    fun primariesByKey(key: String): List<AuthorProfile>

    /**
     * [accountId] 가 [since] 이후(`created_at >= since`)에 **사용자가 만든** 프로필 줄 수 — `primary_of` 가 NULL 인 줄만, **지워진 것 포함**. 만들기 한도의 근거다:
     * 표에서 세므로 재시작 · 인스턴스 여럿에도 같고, 이름이 겹쳐 실패한 시도(줄이 안 생긴다)는 세지 않는다. 첫 프로필은 세지 않는다. 탈퇴로 줄이 지워지면 함께 사라진다.
     */
    fun countCreatedSince(accountId: String, since: Instant): Int

    /**
     * **첫 프로필**(`primary_of` 가 있는 줄)을 넣는다. `primary_of` 나 (키, 꼬리표) 가 이미 있으면 **유니크 위반으로** false (아무것도 바뀌지 않는다, insert-ignore) —
     * 어느 쪽인지는 알리지 않는다(부르는 쪽이 [findPrimary] 로 가른다). 동시에 넣어도 한 쪽만 true 다.
     */
    fun insert(profile: AuthorProfile): Boolean

    /**
     * **사용자가 만든** 프로필(`primary_of` 가 NULL)을 상한 검사와 **함께, 원자적으로** 넣는다. 구현 계약(B5b):
     *  - 한 트랜잭션에서 그 계정의 **첫 프로필 줄을 잠그고**(`select … where primary_of = :account for update`) → 살아 있는 줄 수를 세고 → [maxActive] 이상이면 [CapInsert.LIMIT_REACHED] →
     *    아니면 넣는다. 같은 계정의 동시 요청이 이 잠금에서 줄을 서므로 상한을 넘지 못한다(같은 계정의 만들기끼리는 잠그는 줄이 하나라 교착이 없다). 개수는 잠금 읽기로 세어 바깥 트랜잭션의 낡은 스냅샷을 보지 않는다. 부르는 쪽([AuthorProfileService.create])이 먼저 첫 프로필 줄을 보장한다 — 줄이 없으면 구현은 잠글 것이 없으니 던져도 된다.
     *  - (키, 꼬리표) 유니크에 걸리면 [CapInsert.NAME_TAKEN] (아무것도 바뀌지 않는다). 상한과 겹침이 함께 해당하면 [CapInsert.LIMIT_REACHED] 가 먼저다.
     *    PostgreSQL 은 실패한 INSERT 가 트랜잭션을 망가뜨리므로 `on conflict do nothing` 으로 판정한다.
     *  - 첫 프로필 줄은 지워졌어도(DELETED) 잠금 대상이다.
     */
    fun insertWithinCap(profile: AuthorProfile, maxActive: Int): CapInsert

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

    /** 탈퇴 — 계정의 프로필 행을 **모두** 지운다(지워진 것 포함: 형제 관계가 DB 에도 남지 않는다). 첫 프로필(앵커) 줄을 먼저 잠근 뒤 지운다(없으면 그냥 지운다). 지운 줄 수. 멱등 */
    fun deleteByAccount(accountId: String): Int
}

enum class RenameResult { DONE, TAKEN, NOT_FOUND }

enum class CapInsert { INSERTED, NAME_TAKEN, LIMIT_REACHED }
