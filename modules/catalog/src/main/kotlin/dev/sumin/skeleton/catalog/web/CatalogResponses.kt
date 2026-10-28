package dev.sumin.skeleton.catalog.web

import dev.sumin.skeleton.catalog.CatalogEntry
import dev.sumin.skeleton.catalog.CatalogPrice
import dev.sumin.skeleton.catalog.CatalogProductText
import dev.sumin.skeleton.catalog.CurrentPrices
import dev.sumin.skeleton.catalog.ProductKind
import dev.sumin.skeleton.catalog.PublicCatalog
import dev.sumin.skeleton.catalog.PublicPrice
import dev.sumin.skeleton.catalog.PublicProduct
import java.time.Instant

data class TextResponse(val name: String, val description: String?)

private fun List<CatalogProductText>.toResponse(): Map<String, TextResponse> = associate { it.locale to TextResponse(it.name, it.description) }

// 공개 읽기 (C-CAT)

data class PublicPriceResponse(
    val code: String,
    val period: String?,
    val amount: Long,
    val currency: String,
    val compareAtAmount: Long?,
    val saleEndsAt: Instant?,
)

data class PublicProductResponse(
    val code: String,
    val kind: ProductKind,
    val level: Int?,
    val highlighted: Boolean,
    val sortOrder: Int,
    val texts: Map<String, TextResponse>,
    val prices: List<PublicPriceResponse>,
)

data class PublicCatalogResponse(val currency: String?, val products: List<PublicProductResponse>)

fun PublicCatalog.toResponse() = PublicCatalogResponse(currency, products.map { it.toResponse() })

private fun PublicProduct.toResponse() = PublicProductResponse(code, kind, level, highlighted, sortOrder, texts.toResponse(), prices.map { it.toResponse() })

private fun PublicPrice.toResponse() = PublicPriceResponse(code, period, amount, currency, compareAtAmount, saleEndsAt)

// 운영 (C-CADM)

/** [current] = 지금 살 수 있는 가격이다(상품이 판매 중이고 이 가격이 열쇠의 현재 가격) */
data class AdminPriceResponse(
    val code: String,
    val period: String?,
    val amount: Long,
    val currency: String,
    val compareAtAmount: Long?,
    val saleStartsAt: Instant?,
    val saleEndsAt: Instant?,
    val selling: Boolean,
    val current: Boolean,
    val stoppedAt: Instant?,
    val providerRefs: Map<String, String>,
)

data class AdminProductResponse(
    val code: String,
    val kind: ProductKind,
    val level: Int?,
    val highlighted: Boolean,
    val selling: Boolean,
    val sortOrder: Int,
    val texts: Map<String, TextResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val prices: List<AdminPriceResponse>,
)

fun CatalogEntry.toAdminResponse(now: Instant): AdminProductResponse {
    val currentCodes = if (product.selling) CurrentPrices.current(prices, now).map { it.code }.toSet() else emptySet()
    return AdminProductResponse(
        product.code, product.kind, product.level, product.highlighted, product.selling, product.sortOrder, product.texts.toResponse(), product.createdAt, product.updatedAt,
        prices.map { it.toAdminResponse(it.code in currentCodes) },
    )
}

fun CatalogPrice.toAdminResponse(current: Boolean) =
    AdminPriceResponse(code, period, amount, currency, compareAtAmount, saleStartsAt, saleEndsAt, selling, current, stoppedAt, providerRefs.toSortedMap())
