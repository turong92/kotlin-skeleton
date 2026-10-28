package dev.sumin.skeleton.catalog

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   catalog:
 *     default-locale: en    # 결제창 이름 · 플랜 이름으로 쓰는 언어 (그 언어 이름이 없는 상품은 첫 언어)
 *     item-type: product    # 단건 주문의 itemType - itemRef 는 가격 코드
 *     http:
 *       enabled: false      # true 면 공개 읽기(로그인 없이 상품 · 가격)가 열린다 (꺼져 있으면 404)
 *     admin:
 *       enabled: false      # true 면 운영자 경로가 열린다 (꺼져 있으면 404)
 * ```
 */
@ConfigurationProperties("skeleton.catalog")
data class CatalogProperties(
    val defaultLocale: String = "en",
    val itemType: String = "product",
    val http: Http = Http(),
    val admin: Admin = Admin(),
) {
    data class Http(
        /** 공개 읽기 `GET /api/v1/catalog/products`. 기본 꺼짐 → 경로가 없어 404 */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/catalog",
    )

    data class Admin(
        /** 운영자 경로(`catalog.manage`). 기본 꺼짐 → 경로가 없어 404 */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin",
    )
}
