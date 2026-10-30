package dev.sumin.skeleton.review

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   review:
 *     target-types: []            # 리뷰를 받을 대상 종류. 기본은 비어 있다 - 앱이 [product] 처럼 적는다
 *     require-purchase: false     # true 면 구매 신호(ReviewPurchases.record)가 있는 사람만 쓴다
 *     body-min-length: 0
 *     body-max-length: 2000
 *     max-photos: 0               # 사진 칸 수 (0 = 사진 없음)
 *     http:
 *       enabled: true
 *       base-path: /api/v1/reviews
 *       allow-anonymous-read: false   # true 면 요약 · 목록 읽기 셋을 로그인 없이 연다
 * ```
 */
@ConfigurationProperties("skeleton.review")
data class ReviewProperties(
    /** 대상 종류 - `[a-z][a-z0-9_-]{0,31}`. 비어 있으면 어떤 대상도 리뷰를 받지 못한다(404 `REVIEW.TARGET_NOT_FOUND`) */
    val targetTypes: List<String> = emptyList(),
    /** 구매자만 쓴다. 기본 꺼짐(누구나 쓰고 구매자에게 `verified` 배지) */
    val requirePurchase: Boolean = false,
    val bodyMinLength: Int = 0,
    val bodyMaxLength: Int = 2000,
    val maxPhotos: Int = 0,
    val http: Http = Http(),
) {
    data class Http(
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/reviews",
        /**
         * 대상 요약 · 일괄 요약 · 리뷰 목록의 `GET` 셋을 로그인 없이 읽게 연다. 기본 `false`(열기는 앱의 선택, `badge` · `board` 의 같은 이름 설정과 같다).
         * 열어도 `config` · `me` · 쓰기 · 도움돼요는 로그인이 필요하다. 속도 제한은 `skeleton.web.rate-limit` 을 따른다 - 열면서 같이 켠다.
         */
        val allowAnonymousRead: Boolean = false,
    )
}
