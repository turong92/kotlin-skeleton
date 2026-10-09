package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentItemNotPurchasableException
import dev.sumin.skeleton.payment.PurchaseQuote
import dev.sumin.skeleton.payment.PurchaseRequest
import java.util.Locale
import org.springframework.stereotype.Component

/** 서버가 가진 응원 금액 한 줄 — `itemRef` 는 이 목록의 코드(예: `cheer-3000`)다. */
data class CheerOption(val itemRef: String, val amount: PaymentAmount)

/**
 * 응원하기 상품 — **금액의 유일한 출처**(스펙 §4.2). 브라우저는 `itemRef` 만 보내고 금액은 이 목록에서 서버가 정한다. 목록에 없는 코드 · 수량이 1 이 아닌 주문 · 다른 상품 종류는
 * `PAYMENT.ITEM_NOT_PURCHASABLE` 이다. 금액을 바꾸려면 [AMOUNTS] 만 고친다(코드는 `cheer-<금액>` 으로 만들어진다).
 */
@Component
class CheerCatalog : PaymentCatalog {
    override fun quote(request: PurchaseRequest): PurchaseQuote {
        if (request.itemType != ITEM_TYPE || request.quantity != 1) throw PaymentItemNotPurchasableException()
        val option = byRef[request.itemRef] ?: throw PaymentItemNotPurchasableException()
        // 결제창에 보이는 이름 — 개인정보가 없다(금액뿐). 로케일을 고정한다: JVM 기본 로케일에 따라 천 단위 기호가 바뀌지 않게
        return PurchaseQuote(option.amount, "응원하기 %,d원".format(Locale.ROOT, option.amount.amount))
    }

    companion object {
        const val ITEM_TYPE = "cheer"
        const val CURRENCY = "KRW"

        /** 1,000 · 3,000 · 5,000원 — 작은 후원의 눈에 보이는 선택지. */
        val AMOUNTS: List<Long> = listOf(1_000L, 3_000L, 5_000L)

        val options: List<CheerOption> = AMOUNTS.map { CheerOption("cheer-$it", PaymentAmount(it, CURRENCY)) }
        private val byRef: Map<String, CheerOption> = options.associateBy { it.itemRef }
    }
}
