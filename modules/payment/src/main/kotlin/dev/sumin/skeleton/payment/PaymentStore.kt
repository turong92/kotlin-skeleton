package dev.sumin.skeleton.payment

import java.time.Instant

/**
 * 앱 스토어(App Store · Google Play)가 판 것을 서버가 **스토어에 직접 물어** 확인하는 결제사 능력. 결제창도 서버 승인도 없다 - 구매는 이미 스토어 안에서 끝났고, 앱이 보낸 영수증은 믿지 않는다(거래 id · 구매 토큰만 받아 우리 자격으로 묻는다).
 *
 * **구현은 [mode] 를 `LIVE` 로 선언한다** - 대시보드 매출은 결제사의 지금 모드와 같은 모드로 만든 주문만 세므로, 스토어 결제사가 `LIVE` 면 거래마다 `TEST` 로 표시된 샌드박스 · 라이선스 테스터 구매
 * ([StorePurchase.mode], 사건의 [PushedPayment.mode])는 매출에서 빠진다. 거래마다 모드가 다르다는 사실은 능력이 아니라 거래에 실린다.
 */
interface PaymentStoreGateway : PaymentGateway {
    /**
     * 스토어에 이 구매를 묻는다. 스토어가 모르면 null(위조 · 다른 앱), 일시 실패(타임아웃 · 429 · 5xx)는 던진다.
     * **락 · 트랜잭션 밖에서 부른다** - 스토어 호출이다. 돌려준 값이 사실이고, 앱이 보낸 값은 [StoreClaim] 이 전부다.
     */
    fun fetch(claim: StoreClaim): StorePurchase?

    /**
     * 지급이 **커밋된 뒤** 부른다(구글 승인 통지: 3일 안에 하지 않으면 자동 환불). 실패해도 지급은 되돌리지 않는다 - 던져도 호출한 쪽이 로그만 남긴다. 기본은 아무것도 안 한다.
     * **멱등이어야 하고 이미 기록된 구매에도 불린다**: 앞선 통지가 실패했거나 커밋과 통지 사이에 서버가 내려갔다면, 앱이 같은 구매를 다시 확인할 때(`ALREADY_RECORDED`)가 유일한 재시도다.
     */
    fun afterGrant(purchase: StorePurchase) = Unit
}

/**
 * 앱이 스토어에서 산 것을 가리키는 값. [reference] 는 애플 거래 id(`transactionId`) 또는 구글 구매 토큰이고, [productId] 는 구글이 어느 API 로 물을지 고르는 힌트일 뿐이다(애플은 null).
 * 구매 토큰은 로그에 싣지 않는다.
 */
data class StoreClaim(val reference: String, val productId: String? = null) {
    override fun toString(): String = "StoreClaim(productId=$productId)"
}

enum class StorePurchaseKind { ONE_TIME, SUBSCRIPTION }

/** 스토어가 말하는 구매의 상태 - [PENDING] 은 아직 결제 대기(구글 보류 결제 · 애플 승인 요청), [REVOKED] 는 환불 · 취소로 효력이 없다. */
enum class StorePurchaseState { PURCHASED, PENDING, REVOKED }

/**
 * 스토어가 알려 준 구매 한 건의 지금 사실. 금액 · 통화는 스토어 값 그대로이고(정산은 스토어 보고서다 - 장부 금액은 참고값), [mode] 는 **이 거래**의 모드다
 * (애플 `Sandbox` · 구글 테스트 구매 → `TEST`, 그 밖 `LIVE`).
 *
 * @property providerPaymentId 결제 번호(애플 `transactionId` · 구글 `orderId`) - 장부의 `(provider, provider_payment_id)` 유니크가 이중 지급을 막는다.
 * @property providerSubscriptionId 구독 번호(애플 `originalTransactionId` · 구글 구매 토큰의 해시). 단건이면 null.
 * @property replacesSubscriptionId 이 구독이 이어받는 옛 구독 번호(구글 `linkedPurchaseToken`) - 우리 줄을 가리키면 옛 줄은 `REPLACED` 로 끝난다.
 * @property accountToken 구매에 박힌 계정 토큰(`appAccountToken` · `obfuscatedAccountId`) - 없으면 null(스토어에서 바로 산 오퍼 코드 등).
 * @property subscriptionStatus 구독의 지금 상태 - 단건이면 null.
 * @property familyShared 가족 공유로 받은 구매다(애플 `inAppOwnershipType=FAMILY_SHARED`) - 지급하지 않는다([quantity] 가 2 이상인 것과 같은 「지급 불가」 사유).
 */
data class StorePurchase(
    val mode: PaymentMode,
    val kind: StorePurchaseKind,
    val productId: String,
    val basePlanId: String? = null,
    val providerPaymentId: String,
    val providerSubscriptionId: String? = null,
    val replacesSubscriptionId: String? = null,
    val accountToken: String? = null,
    val amount: PaymentAmount,
    val quantity: Int = 1,
    val paidAt: Instant,
    val periodStart: Instant? = null,
    val periodEnd: Instant? = null,
    val state: StorePurchaseState,
    val subscriptionStatus: PushedSubscriptionStatus? = null,
    val familyShared: Boolean = false,
) {
    /** 계정 토큰은 계정 id 의 해시지만 로그에 싣지 않는다. */
    override fun toString(): String = "StorePurchase(kind=$kind, mode=$mode, productId=$productId, state=$state)"
}

/** 이 결제사가 스토어인가(켜진 스토어 결제사 중에 있는가) - 스토어가 과금하는 구독에는 서버가 해지 · 변경을 하지 않는다. */
internal fun PaymentProviderRouter.isStore(providerId: String): Boolean = providerId in providerIds(PaymentStoreGateway::class)
