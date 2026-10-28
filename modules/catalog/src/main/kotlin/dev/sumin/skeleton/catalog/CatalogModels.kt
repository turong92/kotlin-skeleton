package dev.sumin.skeleton.catalog

import java.time.Instant

/** 상품의 종류 - 만든 뒤 바꾸지 않는다. 구독 상품은 가격에 주기와 등급이 있고, 단건 상품은 주기가 없다. */
enum class ProductKind { SUBSCRIPTION, ONE_TIME }

/** 한 언어의 이름 · 설명. 결제창 이름은 [CatalogProperties.defaultLocale] 의 [name] 이다. */
data class CatalogProductText(val locale: String, val name: String, val description: String? = null)

/**
 * 파는 것 하나(예: basic). 가격은 [CatalogPrice] 가 따로 갖는다 - 같은 상품에 월 · 연 · 통화별 가격이 여럿이다.
 * [level] 은 구독 상품만 갖는 등급(높을수록 윗등급, 상품끼리 겹치지 않는다).
 */
data class CatalogProduct(
    val id: String,
    val code: String,
    val kind: ProductKind,
    val level: Int?,
    val highlighted: Boolean,
    val selling: Boolean,
    val sortOrder: Int,
    val texts: List<CatalogProductText>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** [locale] 의 이름, 없으면 첫 언어 - 언어가 하나도 없는 상품은 만들 수 없으니 늘 있다 */
    fun textIn(locale: String): CatalogProductText = texts.firstOrNull { it.locale == locale } ?: texts.first()
}

/**
 * 가격 한 줄 - **만든 뒤 금액 · 통화 · 주기 · 정가를 고치지 않는다**(바꾸려면 새 가격 + 옛 가격 판매 중지). 고칠 수 있는 것은 [saleEndsAt] 과 결제사 링크뿐이다.
 * [code] 가 구독 줄의 `plan_code` · 단건의 `itemRef` · 결제사 링크의 열쇠다. [amount] 는 통화 최소 단위(KRW 원 · USD 센트).
 * [compareAtAmount] 는 정가(행사가가 붙은 가격에서 취소선), 판매 기간([saleStartsAt] · [saleEndsAt]) 이 있으면 행사 가격이고 둘 다 없으면 상시 가격이다. 끝은 열린 구간(끝 시각 정각에는 이미 아니다).
 */
data class CatalogPrice(
    val id: String,
    val productId: String,
    val code: String,
    val period: String?,
    val amount: Long,
    val currency: String,
    val compareAtAmount: Long?,
    val saleStartsAt: Instant?,
    val saleEndsAt: Instant?,
    val selling: Boolean,
    val stoppedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 결제사 id → 그 결제사의 가격 id (Paddle `pri_…`) */
    val providerRefs: Map<String, String> = emptyMap(),
) {
    /** 판매 기간이 있는 가격 - 행사. 둘 다 없으면 상시 */
    val windowed: Boolean get() = saleStartsAt != null || saleEndsAt != null
}

/** 상품 하나와 그 가격 전부(지난 가격 · 판매 중지까지) - 운영 화면의 한 줄 */
data class CatalogEntry(val product: CatalogProduct, val prices: List<CatalogPrice>)
