package dev.sumin.skeleton.apikey

import java.time.Instant

/**
 * 키 저장 포트. 구현은 `api-key-jdbc` 가 낸다 - 메모리 구현은 없다(회수 · 만료가 재시작에 사라지면 안 된다).
 * 조건부 갱신([revoke] · [touchLastUsed])은 동시에 와도 한쪽만 이기게 저장소가 한 문장으로 처리한다.
 */
interface ApiKeyStore {
    fun insert(key: ApiKey)

    fun findById(id: String): ApiKey?

    fun findByTokenHash(hash: String): ApiKey?

    /** 이 주인의 키 전부(회수 · 만료 포함), 만든 순 최신이 먼저 */
    fun listByOwner(ownerType: String, ownerId: String): List<ApiKey>

    /**
     * 운영 목록 - 모든 주인의 키를 id 내림차순(발급이 새것인 순)으로 [limit] 개. [before] 는 이 id 보다 앞(옛것)만, [ownerId]·[status] 는 주어졌을 때만 거른다.
     * 상태는 [now] 로 가른다(회수 > 만료 > 활성)
     */
    fun page(ownerId: String?, status: ApiKeyStatus?, before: String?, limit: Int, now: Instant): List<ApiKey>

    /** 회수되지 않았고 [now] 에 아직 만료되지 않은 키의 수 */
    fun countActive(ownerType: String, ownerId: String, now: Instant): Int

    /** 아직 회수되지 않은 키를 회수한다 - 이미 회수됐거나 없으면 false. [reason] 은 운영 회수가 적는다 */
    fun revoke(id: String, by: String, reason: String?, now: Instant): Boolean

    /** 마지막 사용을 적는다 - `last_used_at` 이 비었거나 [staleBefore] 보다 옛것일 때만(1분 해상도, 쓰기 증폭 방지). 썼으면 true */
    fun touchLastUsed(id: String, now: Instant, ip: String?, staleBefore: Instant): Boolean

    /** 탈퇴 때 - 이 주인의 키를 모두 지운다(멱등) */
    fun deleteByOwner(ownerType: String, ownerId: String): Int
}
