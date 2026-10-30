package dev.sumin.skeleton.review

/**
 * 대상이 진짜 있는지 앱이 답하는 자리(견본: 상품 코드를 `catalog` 에서 확인). `review` 는 상품이 무엇인지 모른다.
 * 앱이 이 타입의 빈을 두지 않으면 id 모양(1~64자)만 본다. [ids] 중 있는 것만 돌려준다.
 */
fun interface ReviewTargets {
    fun existing(type: String, ids: Collection<String>): Set<String>
}

/**
 * 누가 쓸 수 있나 · 무엇을 붙일 수 있나 · 누가 운영하나를 앱이 좁히는 자리(`BoardPolicy` 와 같은 방식). 기본은 모두 허용(운영은 거절).
 * `canAttach` · `canModerate` 는 사진 · 운영 조각이 쓴다.
 */
interface ReviewPolicy {
    fun canWrite(accountId: String, target: ReviewTarget): Boolean = true

    /** 사진 저장 키를 붙여도 되나 - 키 소유 확인은 앱이 `StorageKeyOwnership` 으로 좁힌다 */
    fun canAttach(accountId: String, keys: List<String>): Boolean = true

    fun canModerate(accountId: String): Boolean = false
}

/**
 * 리뷰가 공개되거나 내려갈 때 **쓰기 트랜잭션 안에서** 불리는 앱의 확장 자리(보상 연결용 - `PaymentOrderListener` 와 같은 규칙: 짧게 하고,
 * **던지면 쓰기도 실패한다**). 여러 개를 둘 수 있고 모두 불린다. 리뷰는 보상을 모른다 - 앱이 `CampaignSignals` 같은 곳에 잇는다.
 */
interface ReviewEventListener {
    /** 공개됐다. [firstTime] = 이 줄이 처음 공개됨(지운 뒤 다시 써서 되살린 것은 false) */
    fun onPublished(review: Review, firstTime: Boolean) {}

    /** 공개에서 내려갔다(작성자가 지웠다) */
    fun onRemoved(review: Review) {}
}

/** 서버 API - 앱이 「이 계정이 산 것」을 알리는 한 줄(결제 리스너 · 외부 주문 · 수동 어디서든). `CampaignSignals` · `Referrals.qualify` 와 같은 모양 */
interface ReviewPurchases {
    /**
     * 구매 신호를 넣는다 - 호출자 트랜잭션에 합류한다. 같은 [ref] 두 번은 한 줄이고, 그 (계정, 대상)의 리뷰는 `verified=true` 가 된다.
     * 모듈을 붙이기 전의 구매는 앱이 한 번 이 호출로 채운다.
     */
    fun record(accountId: String, target: ReviewTarget, ref: String)

    /** 구매를 취소한다(전액 환불). 그 대상에 다른 살아 있는 구매가 없으면 리뷰의 `verified` 가 내려간다. 모르는 [ref] 는 아무 일도 없다 */
    fun revoke(ref: String)
}

/** 서버 API - 앱이 자기 상품 목록 응답에 평균을 끼울 때(일괄 조회) */
interface ReviewStats {
    /** [targets] 마다 하나 - 리뷰가 없는 대상은 `count=0`, `average=null` */
    fun of(targets: Collection<ReviewTarget>): Map<ReviewTarget, RatingSummary>
}

/** 서버 API - 업적 수치용 */
interface Reviews {
    /** 그 계정의 리뷰 줄 수 - 줄은 지워지지 않으니 늘기만 한다 */
    fun countWritten(accountId: String): Int
}
