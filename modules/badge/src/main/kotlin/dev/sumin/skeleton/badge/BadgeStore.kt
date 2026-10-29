package dev.sumin.skeleton.badge

import java.time.Instant

/**
 * 배지 저장 포트 — `badge-jdbc` 가 구현한다. 쓰기는 모두 [inTransaction] 안(또는 호출자 트랜잭션 안)에서 부르고,
 * 상태 전이는 조건부 갱신이라 동시에 와도 한쪽만 이긴다(바뀐 줄이 있으면 true).
 */
interface BadgeStore {
    /** 열린 트랜잭션이 있으면 거기에 합류하고, 없으면 새로 연다 */
    fun <T> inTransaction(block: () -> T): T

    // 정의

    /** 같은 코드가 없을 때만 넣는다. 넣었으면 true */
    fun insertDefinition(definition: BadgeDefinition): Boolean

    fun findDefinition(id: String): BadgeDefinition?

    fun findDefinitionByCode(code: String): BadgeDefinition?

    fun findDefinitions(ids: Collection<String>): Map<String, BadgeDefinition>

    /** `sort_order`, id 순 */
    fun listDefinitions(): List<BadgeDefinition>

    /** 배지 id → 지금 가진(`ACTIVE`) 사람 수. 아무도 없는 배지는 빠진다 */
    fun holderCounts(): Map<String, Int>

    /** 이름 · 설명 · 그림 · 숨김 · 순서만 바꾼다(코드 · 상태는 아니다) */
    fun updateDefinition(definition: BadgeDefinition): Boolean

    fun updateDefinitionStatus(id: String, status: BadgeStatus, at: Instant): Boolean

    // 보유

    /** (배지, 계정)의 보유 줄을 잠그고 읽는다(현재 값) */
    fun lockAward(badgeId: String, accountId: String): BadgeAward?

    fun lockAwardById(id: String): BadgeAward?

    /** (배지, 계정) 줄이 없을 때만 넣는다. 넣었으면 true */
    fun insertAwardIfAbsent(award: BadgeAward): Boolean

    /** `REVOKED` → `ACTIVE`(받은 시각을 [at] 으로, 회수 시각은 비운다) */
    fun reactivateAward(id: String, at: Instant): Boolean

    /** `ACTIVE` → `REVOKED` */
    fun revokeAward(id: String, at: Instant): Boolean

    /** 본인 줄(`ACTIVE`)의 프로필 노출을 바꾼다. 남의 줄 · 회수된 줄이면 false */
    fun setVisible(id: String, accountId: String, visible: Boolean): Boolean

    /** 이 계정이 지금 가진(`ACTIVE`) 줄, `sort_order` 순 */
    fun activeAwardsOf(accountId: String): List<BadgeAward>

    /**
     * 계정마다 `ACTIVE ∧ visible` 인 배지만 읽는다 — **걸러 내기가 아니라 안 읽기**다(공개 응답의 근거). `sort_order`, id 순. 배지 없는 계정은 키가 없다.
     */
    fun publicBadgesOf(accountIds: Collection<String>): Map<String, List<PublicBadge>>

    // 내역

    fun hasEvent(action: BadgeAction, source: BadgeSource, sourceRef: String): Boolean

    /** `(action, source, sourceRef)` 가 없을 때만 넣는다(`sourceRef` 가 null 이면 항상 넣는다). 넣었으면 true */
    fun insertEventIfAbsent(event: BadgeAwardEvent): Boolean

    /** 최신순. [before] 는 직전 쪽의 마지막 id */
    fun pageEvents(badgeId: String?, accountId: String?, before: String?, limit: Int): List<BadgeAwardEvent>

    // 탈퇴

    /** 그 계정의 보유 줄과 내역을 지운다. 두 번 불러도 같다. 지운 줄 수를 돌려준다 */
    fun eraseAccount(accountId: String): Int
}
