package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentStoreGateway
import dev.sumin.skeleton.payment.StoreClaim
import dev.sumin.skeleton.payment.StorePurchase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 앱 스토어 모양 결제사의 대역: 서명된 사건이 곧 사실이고([FakePushGateway]) 구매는 스토어에 물어 확인한다([PaymentStoreGateway]). 스토어가 아는 구매는 [known] 에 심는다.
 * 결제사 모드는 계약대로 `LIVE` 다.
 */
class FakeStoreGateway(providerId: String = "appstore") : FakePushGateway(providerId, PaymentMode.LIVE), PaymentStoreGateway {
    override val mode: PaymentMode get() = PaymentMode.LIVE

    /** 스토어가 아는 구매 - 구매를 가리키는 값(거래 id · 구매 토큰) → 구매. */
    val known = ConcurrentHashMap<String, StorePurchase>()
    val acknowledged = CopyOnWriteArrayList<StorePurchase>()

    /** 있으면 [fetch] 가 이것을 던진다 - 스토어가 안 받는 때(타임아웃 · 429 · 5xx)의 대역. */
    @Volatile var fetchFailure: RuntimeException? = null

    /** 있으면 [afterGrant] 가 이것을 던진다(승인 통지가 5xx · 시간 초과로 실패하는 때의 대역) - 던진 호출은 [acknowledged] 에 남지 않는다. */
    @Volatile var afterGrantFailure: RuntimeException? = null

    override fun fetch(claim: StoreClaim): StorePurchase? {
        fetchFailure?.let { throw it }
        return known[claim.reference]
    }

    override fun afterGrant(purchase: StorePurchase) {
        afterGrantFailure?.let { throw it }
        acknowledged += purchase
    }
}
