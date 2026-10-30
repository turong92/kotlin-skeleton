package dev.sumin.skeleton.review.storage

import dev.sumin.skeleton.review.ReviewAutoConfiguration
import dev.sumin.skeleton.review.ReviewPolicy
import dev.sumin.skeleton.storage.StorageKeyOwnership
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** 사진 키는 호출자 자신이 올린 것만 - 소유 판정은 `storage` 의 [StorageKeyOwnership](`key-owner` 방식에 맞는 구현)이 한다. 키는 서명 주소로 열리므로 남의 키는 붙일 수 없다 */
class StorageReviewPolicy(private val ownership: StorageKeyOwnership) : ReviewPolicy {
    override fun canAttach(accountId: String, keys: List<String>): Boolean = keys.all { ownership.owns(accountId, it) }
}

/**
 * `storage` 가 클래스패스에 있고 [StorageKeyOwnership] 빈이 있고 앱이 [ReviewPolicy] 를 안 뒀을 때만 - [ReviewAutoConfiguration] 의 기본(사진 거절)보다 먼저 등록해 그쪽을 물린다.
 * 앱이 [ReviewPolicy] 빈을 두면 그것이 이긴다(그때는 `canAttach` 를 직접 덮어 소유를 확인한다).
 */
@AutoConfiguration(before = [ReviewAutoConfiguration::class], afterName = ["dev.sumin.skeleton.storage.StorageAutoConfiguration"])
@ConditionalOnClass(StorageKeyOwnership::class)
class ReviewStorageAutoConfiguration {
    @Bean
    @ConditionalOnBean(StorageKeyOwnership::class)
    @ConditionalOnMissingBean(ReviewPolicy::class)
    fun storageReviewPolicy(ownership: StorageKeyOwnership): ReviewPolicy = StorageReviewPolicy(ownership)
}
