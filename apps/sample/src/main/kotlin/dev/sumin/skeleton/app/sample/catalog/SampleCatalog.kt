package dev.sumin.skeleton.app.sample.catalog

import dev.sumin.skeleton.app.sample.cheer.CheerCatalog
import dev.sumin.skeleton.catalog.CatalogPaymentCatalog
import dev.sumin.skeleton.catalog.CatalogProperties
import dev.sumin.skeleton.catalog.CatalogStore
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PurchaseQuote
import dev.sumin.skeleton.payment.PurchaseRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * 이 앱의 `PaymentCatalog` - 빈은 하나뿐이라 `itemType` 이 `cheer` 면 코드 상품(응원하기, [CheerCatalog]), 아니면 카탈로그 표의 단건 상품([CatalogPaymentCatalog])으로 보낸다.
 * 구독 플랜은 이 길이 아니라 `catalog` 가 낸 `SubscriptionPlans` 가 맡는다.
 */
@Component
class SampleCatalog(store: CatalogStore, time: ObjectProvider<TimeProvider>, properties: CatalogProperties) : PaymentCatalog {
    private val cheer = CheerCatalog()
    private val table = CatalogPaymentCatalog(store, time.getIfAvailable { TimeProvider.systemUtc() }, properties)

    override fun quote(request: PurchaseRequest): PurchaseQuote = if (request.itemType == CheerCatalog.ITEM_TYPE) cheer.quote(request) else table.quote(request)
}
