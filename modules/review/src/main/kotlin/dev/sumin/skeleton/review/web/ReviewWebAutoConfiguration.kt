package dev.sumin.skeleton.review.web

import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.review.ReviewAutoConfiguration
import dev.sumin.skeleton.review.ReviewPolicy
import dev.sumin.skeleton.review.ReviewProperties
import dev.sumin.skeleton.review.ReviewReader
import dev.sumin.skeleton.review.ReviewService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 사용자 경로(`skeleton.review.http.enabled` 기본 켜짐). 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만.
 */
@AutoConfiguration(after = [ReviewAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class ReviewWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(ReviewService::class, ReviewReader::class)
    @ConditionalOnProperty(prefix = "skeleton.review.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun reviewController(reader: ReviewReader, service: ReviewService, properties: ReviewProperties, policy: ObjectProvider<ReviewPolicy>): ReviewController =
        ReviewController(reader, service, properties) { policy.getObject() }

    /**
     * 읽기 셋(대상 요약 · 일괄 요약 · 리뷰 목록)의 `GET` 만 로그인 없이 연다. 기본은 닫혀 있고 `skeleton.review.http.allow-anonymous-read=true` 로 연다(앱의 선택).
     * `config` · `me` · 쓰기 · 도움돼요는 열리지 않는다. 앱은 같은 빈 이름으로 덮어쓸 수 있다.
     */
    @Bean
    @ConditionalOnBean(ReviewReader::class)
    @ConditionalOnMissingBean(name = ["reviewPublicEndpointContributor"])
    @ConditionalOnProperty(prefix = "skeleton.review.http", name = ["allow-anonymous-read"], havingValue = "true")
    fun reviewPublicEndpointContributor(properties: ReviewProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            if (properties.http.enabled) {
                val base = properties.http.basePath
                registry.add("GET", "$base/targets/*/*/summary")
                registry.add("GET", "$base/targets/*/*/reviews")
                registry.add("GET", "$base/summaries")
            }
        }
}
