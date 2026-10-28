package dev.sumin.skeleton.catalog

import java.time.Instant

/**
 * 카탈로그 저장 포트 - `catalog-jdbc` 가 구현한다. 쓰기는 모두 [inTransaction] 안(또는 호출자 트랜잭션 안)에서 부르고,
 * 같은 상품의 가격을 만들 때는 [lockProduct] 로 상품 줄을 먼저 잠가 겹침 검사와 넣기 사이에 다른 쓰기가 끼지 못하게 한다.
 */
interface CatalogStore {
    /** 열린 트랜잭션이 있으면 거기에 합류하고, 없으면 새로 연다 */
    fun <T> inTransaction(block: () -> T): T

    // 상품

    /** 보이는 순서(정렬 값, 만든 순서)대로, 언어별 이름까지 */
    fun listProducts(): List<CatalogProduct>

    fun findProduct(code: String): CatalogProduct?

    fun findProductById(id: String): CatalogProduct?

    /** 상품 줄을 잠그고 읽는다 - 같은 상품에 가격을 만드는 쓰기들이 줄을 선다 */
    fun lockProduct(code: String): CatalogProduct?

    /** 이 등급을 쓰는 상품의 코드 - 없으면 null */
    fun productCodeOfLevel(level: Int): String?

    /** 코드 · 등급이 겹치는지는 호출자가 먼저 확인한다. 동시에 와서 걸리는 유니크 위반은 저장소가 [CatalogException](`CODE_TAKEN` · `LEVEL_TAKEN`)으로 던진다 */
    fun insertProduct(product: CatalogProduct)

    /** 고칠 수 있는 칸(등급 · 인기 · 판매 중 · 정렬 · 언어별 이름)을 바꾼다. 코드 · 종류는 그대로 */
    fun updateProduct(product: CatalogProduct)

    // 가격

    /** 모든 가격 - 상품과 상관없이 (공개 읽기 · 플랜 목록이 한 번에 읽는다), 결제사 링크 포함 */
    fun listPrices(): List<CatalogPrice>

    fun listPricesOf(productId: String): List<CatalogPrice>

    fun findPrice(code: String): CatalogPrice?

    /** 가격 코드가 겹치면 저장소가 [CatalogException](`CODE_TAKEN`)으로 던진다 */
    fun insertPrice(price: CatalogPrice)

    /** 판매 중 → 중지. 이미 중지됐으면 false (되돌리지 않는다) */
    fun stopPrice(code: String, at: Instant): Boolean

    fun updateSaleEnd(code: String, endsAt: Instant?, at: Instant): Boolean

    // 결제사 링크

    /** 링크를 둔다(있으면 바꾼다). 그 결제사 가격 id 가 다른 가격에 이미 걸려 있으면 false */
    fun putProviderRef(priceCode: String, provider: String, externalId: String, at: Instant): Boolean

    fun deleteProviderRef(priceCode: String, provider: String): Boolean

    /** 가격 코드 → 그 결제사의 가격 id */
    fun providerRefs(provider: String): Map<String, String>

    fun providerRefOf(provider: String, priceCode: String): String?
}
