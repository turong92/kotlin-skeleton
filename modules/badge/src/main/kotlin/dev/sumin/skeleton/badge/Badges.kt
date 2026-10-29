package dev.sumin.skeleton.badge

/**
 * 앱이 부르는 서버 API — 앱 고유 조건(첫 글 · 첫 결제)은 앱 코드가 가장 잘 안다. 브라우저에 열려 있지 않다.
 * 호출자 트랜잭션에 합류한다(없으면 자기 트랜잭션). 배지는 포인트 · 쿠폰을 모른다 — 같이 주고 싶으면 앱이 자기 코드에서 지급을 나란히 부른다.
 */
interface Badges {
    /**
     * [code] 배지를 [accountId] 에게 준다. 이미 가졌으면 그 줄을 돌려준다(`newlyAwarded=false`). 같은 [sourceRef] 로 이미 처리한 호출은 다시 주지 않는다 —
     * 회수된 뒤에 같은 [sourceRef] 로 다시 불러도 그대로다(무엇이 원인인지를 [sourceRef] 에 적는다, 60자까지).
     * 정의가 없으면 404 `BADGE.NOT_FOUND`, 보관됐으면 409 `BADGE.ARCHIVED`. 새로 받은 경우에만 커밋 뒤 알림이 나간다.
     */
    fun award(accountId: String, code: String, sourceRef: String): BadgeHolding

    /** 회수한다(사유 필수). 가지고 있지 않았으면 false */
    fun revoke(accountId: String, code: String, reason: String): Boolean

    /**
     * 계정마다 지금 **프로필에 보이는**(보유 ∧ 본인이 숨기지 않은) 배지를 `sort_order` 순으로 — 앱이 자기 프로필 응답에 배지를 끼울 때 쓴다.
     * 보유 줄 id · 받은 시각 · 숨긴 배지는 나가지 않는다. 배지가 없는 계정은 빈 목록으로 들어 있다.
     */
    fun holdingsOf(accountIds: Collection<String>): Map<String, List<PublicBadge>>
}
