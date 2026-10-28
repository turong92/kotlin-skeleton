package dev.sumin.skeleton.coupon

import java.time.Instant

/**
 * 쿠폰 저장 포트 — `coupon-jdbc` 가 구현한다. 쓰기는 모두 [inTransaction] 안(또는 호출자 트랜잭션 안)에서 부르고,
 * 상태 전이는 전부 조건부 갱신이라 동시에 와도 한쪽만 이긴다(바뀐 줄이 있으면 true).
 */
interface CouponStore {
    /** 열린 트랜잭션이 있으면 거기에 합류하고, 없으면 새로 연다 */
    fun <T> inTransaction(block: () -> T): T

    // 정의

    fun insertDefinition(definition: CouponDefinition)

    fun findDefinition(id: String): CouponDefinition?

    /** 정의 줄을 잠그고 읽는다 — 발급 한도 · 계정당 한도를 세는 동안 다른 발급을 세운다 */
    fun lockDefinition(id: String): CouponDefinition?

    fun findDefinitions(ids: Collection<String>): Map<String, CouponDefinition>

    /** 새것부터 */
    fun listDefinitions(limit: Int): List<CouponDefinition>

    fun updateDefinitionStatus(id: String, status: CouponDefinitionStatus, at: Instant): Boolean

    fun incrementIssued(id: String, at: Instant)

    // 현황 - 운영 화면

    /** 한 정의의 쿠폰 수를 [now] 로 읽은 상태별로(기한이 지난 `AVAILABLE` 은 `EXPIRED`). 줄이 없는 상태는 빠진다 */
    fun countByStatus(definitionId: String, now: Instant): Map<CouponStatus, Int>

    /** 한 정의에서 쓰인(`USED`) 쿠폰의 id 를 [after] 다음부터 id 순으로 [limit] 개 - 할인 합계를 덩어리로 물으려고 쪽을 넘긴다 */
    fun usedCouponIds(definitionId: String, after: String?, limit: Int): List<String>

    /** 한 정의의 쿠폰, 새것부터. [status] 는 [now] 로 읽은 상태. [before] 는 직전 쪽의 마지막 id */
    fun pageByDefinition(definitionId: String, status: CouponStatus?, before: String?, limit: Int, now: Instant): List<Coupon>

    // 쿠폰

    /** 같은 `(sourceType, sourceRef)` 가 없을 때만 넣는다. 넣었으면 true */
    fun insertCouponIfAbsent(coupon: Coupon): Boolean

    fun findCoupon(id: String): Coupon?

    /** 출처로 찾으며 줄을 잠근다(현재 값을 읽는다) — 같은 출처의 발급이 두 번 와도 한 장 */
    fun lockBySource(sourceType: CouponSource, sourceRef: String): Coupon?

    /** 한 계정이 한 정의로 받은 쿠폰을 잠그고 읽는다(현재 값) */
    fun lockByAccountAndDefinition(accountId: String, definitionId: String): List<Coupon>

    /** 새것부터 */
    fun listByAccount(accountId: String, limit: Int): List<Coupon>

    /** 마지막으로 이 주문이 쥔 쿠폰 */
    fun findByHolder(holderRef: String): Coupon?

    /** `AVAILABLE`(기한 안) → `RESERVED(holderRef)`. 내 계정의 쿠폰일 때만 */
    fun reserve(id: String, accountId: String, holderRef: String, now: Instant): Boolean

    /** 이 주문이 쥔(`RESERVED`) 쿠폰 → `USED`. 풀렸던(`AVAILABLE` · `EXPIRED`) 쿠폰도 아직 이 주문의 것이면 잡아 `USED` */
    fun confirm(holderRef: String, now: Instant): Boolean

    /** 이 주문이 쥔(`RESERVED`) 쿠폰 → `AVAILABLE`(기한이 지났으면 `EXPIRED`) */
    fun release(holderRef: String, now: Instant): Boolean

    /** 이 주문이 쓴(`USED`) 쿠폰 → `AVAILABLE`(기한이 지났으면 `EXPIRED`) */
    fun restore(holderRef: String, now: Instant): Boolean

    /** `AVAILABLE` → `REVOKED` */
    fun revoke(id: String, now: Instant): Boolean

    // 공용 코드

    fun insertCode(code: CouponCodeRecord)

    fun findCode(code: String): CouponCodeRecord?

    /** 코드 줄을 잠그고 읽는다 — 한도를 세는 동안 다른 등록을 세운다 */
    fun lockCode(code: String): CouponCodeRecord?

    /** 이 계정이 이미 등록했나(현재 값) */
    fun hasRedeemed(code: String, accountId: String): Boolean

    fun insertRedemption(code: String, accountId: String)

    fun incrementRedeemed(code: String)

    // 탈퇴

    /**
     * 계정이 지워질 때: 그 계정의 쿠폰 줄은 지우지 않고 `account_id` 만 [tombstone] 으로 바꾼다(발급 · 사용 통계와 결제 장부와의 연결을 남기고 개인 연결만 끊는다).
     * 아직 쓰지 않은(`AVAILABLE`) 쿠폰은 쓸 수 없게 `REVOKED` 로 둔다 - 현황의 「쓸 수 있는」 수에 죽은 쿠폰이 남지 않게. 주문이 쥔(`RESERVED`) 쿠폰은 그 주문이 풀거나 확정한다.
     * 공용 코드 등록 줄(계정과의 연결뿐)은 지운다. 바꾼 쿠폰 수를 돌려주며 두 번 불러도 같다.
     */
    fun anonymizeAccount(accountId: String, tombstone: String, now: Instant): Int
}
