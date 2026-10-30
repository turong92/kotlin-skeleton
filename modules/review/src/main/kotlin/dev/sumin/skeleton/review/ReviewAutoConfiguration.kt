package dev.sumin.skeleton.review

import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.review.erasure.ReviewAccountErasureListener
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 리뷰 쓰기 · 읽기 서비스, 구매 신호 [ReviewPurchases], 앱 API([ReviewStats] · [Reviews]), 탈퇴 고리. 저장소([ReviewStore])는 `review-jdbc` 가 내놓는다 -
 * 없으면 서비스 빈이 켜지지 않는다. 상품 · 결제 · 보상을 모른다 - 대상 확인은 앱의 [ReviewTargets], 보상은 앱의 [ReviewEventListener] 가 잇는다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.review.jdbc.ReviewJdbcAutoConfiguration"])
@EnableConfigurationProperties(ReviewProperties::class)
class ReviewAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReviewPolicy::class)
    fun defaultReviewPolicy(): ReviewPolicy = object : ReviewPolicy {}

    @Bean
    @ConditionalOnMissingBean
    fun reviewTargetRules(properties: ReviewProperties, targets: ObjectProvider<ReviewTargets>): ReviewTargetRules =
        ReviewTargetRules(properties) { targets.getIfAvailable() }

    @Bean
    @ConditionalOnMissingBean
    fun reviewContentRules(properties: ReviewProperties): ReviewContentRules = ReviewContentRules(properties)

    @Bean
    @ConditionalOnBean(ReviewStore::class)
    @ConditionalOnMissingBean
    fun reviewService(
        store: ReviewStore,
        properties: ReviewProperties,
        content: ReviewContentRules,
        targets: ReviewTargetRules,
        policy: ObjectProvider<ReviewPolicy>,
        listeners: ObjectProvider<ReviewEventListener>,
        time: ObjectProvider<TimeProvider>,
    ): ReviewService = ReviewService(
        store, properties, content, targets, { policy.getObject() }, { listeners.orderedStream().toList() }, time.getIfAvailable { TimeProvider.systemUtc() },
    )

    @Bean
    @ConditionalOnBean(ReviewStore::class)
    @ConditionalOnMissingBean(ReviewPurchases::class)
    fun reviewPurchases(store: ReviewStore, time: ObjectProvider<TimeProvider>): ReviewPurchases =
        ReviewPurchaseService(store, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(ReviewStore::class)
    @ConditionalOnMissingBean
    fun reviewReader(
        store: ReviewStore,
        properties: ReviewProperties,
        targets: ReviewTargetRules,
        policy: ObjectProvider<ReviewPolicy>,
        directory: ObjectProvider<AuthorDirectory>,
    ): ReviewReader = ReviewReader(store, properties, targets, { policy.getObject() }, { directory.getIfAvailable { AuthorDirectory.NONE } })

    @Bean
    @ConditionalOnBean(ReviewStore::class)
    @ConditionalOnMissingBean(name = ["reviewAccountErasureListener"])
    fun reviewAccountErasureListener(store: ReviewStore): AccountErasureListener = ReviewAccountErasureListener(store)
}
