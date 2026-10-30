package dev.sumin.skeleton.review

/**
 * 대상이 진짜 있는지 앱이 답하는 자리(견본: 상품 코드를 `catalog` 에서 확인). `review` 는 상품이 무엇인지 모른다.
 * 앱이 이 타입의 빈을 두지 않으면 id 모양(1~64자)만 본다. [ids] 중 있는 것만 돌려준다.
 */
fun interface ReviewTargets {
    fun existing(type: String, ids: Collection<String>): Set<String>
}

/**
 * 누가 쓸 수 있나 · 무엇을 붙일 수 있나 · 누가 운영하나를 앱이 **좁히는** 자리(`BoardPolicy` 와 같은 방식). 쓰기 · 운영은 기본 허용(거절만 한다), 사진 붙이기만 기본 거절이다.
 * `canModerate` 는 운영 권한(`review.moderate`)을 이미 가진 사람에게 앱이 한 번 더 거절할 때만 쓴다(권한을 주는 자리가 아니다).
 */
interface ReviewPolicy {
    fun canWrite(accountId: String, target: ReviewTarget): Boolean = true

    /**
     * 사진 저장 키를 붙여도 되나. **기본은 거절이다** - 키가 호출자 자신의 업로드인지 `review` 는 모른다(서명 주소로 열어 주므로 남의 비공개 키를 붙이게 둘 수 없다).
     * `storage` 가 클래스패스에 있고 앱이 정책 빈을 안 두면 `StorageKeyOwnership` 으로 좁힌 정책이 자동으로 선다. 직접 정책을 두면 이 메서드를 덮어 소유를 확인한다.
     */
    fun canAttach(accountId: String, keys: List<String>): Boolean = false

    fun canModerate(accountId: String): Boolean = true
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

/**
 * 운영자가 숨겼을 때 · 답글을 달았을 때 작성자에게 알리는 자리 - **커밋 뒤에** 불리고 던져도 운영 처리는 성공한다.
 * 기본은 아무것도 하지 않는다. `notification` 이 있으면 받은편지함으로 보낸다(`NotificationReviewNotifier`).
 */
interface ReviewNotifier {
    /** [reason] 은 운영자가 적은 사유 - 자동 가림이면 `AUTO_REPORTS` */
    fun hidden(accountId: String, review: Review, reason: String) {}

    fun replied(accountId: String, review: Review) {}
}
