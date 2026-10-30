package dev.sumin.skeleton.app.sample.reviews

import dev.sumin.skeleton.campaign.CampaignSignals
import dev.sumin.skeleton.catalog.CatalogStore
import dev.sumin.skeleton.review.Review
import dev.sumin.skeleton.review.ReviewEventListener
import dev.sumin.skeleton.review.ReviewPolicy
import dev.sumin.skeleton.review.ReviewTargets
import dev.sumin.skeleton.review.Reviews
import dev.sumin.skeleton.storage.StorageKeyOwnership
import org.springframework.stereotype.Component

/** 리뷰 대상 `product` 는 카탈로그 상품 코드 - 있는 코드만 리뷰를 받는다. `review` 는 상품이 무엇인지 모른다 */
@Component
class SampleReviewTargets(private val store: CatalogStore) : ReviewTargets {
    override fun existing(type: String, ids: Collection<String>): Set<String> =
        if (type == ReviewPurchaseSignals.TARGET_TYPE) ids.filter { store.findProduct(it) != null }.toSet() else emptySet()
}

/** 사진 키는 호출자 자신이 올린 것이어야 한다 - 소유 판정은 storage 의 [StorageKeyOwnership] (`OPAQUE` 키라 계정 id 가 키에 없다) */
@Component
class SampleReviewPolicy(private val ownership: StorageKeyOwnership) : ReviewPolicy {
    override fun canAttach(accountId: String, keys: List<String>): Boolean = keys.all { ownership.owns(accountId, it) }
}

/**
 * 리뷰 → 보상. 리뷰를 처음 공개할 때 계정의 리뷰 개수를 업적 수치 `reviews.written` 으로 보고한다(같은 쓰기 트랜잭션 안 - 던지면 쓰기도 실패한다).
 * 단계 보상(「첫 리뷰」 배지 + 100포인트)은 [ReviewsSeed] 의 업적 이벤트 설정이다. `review` 는 보상을 모른다.
 */
@Component
class ReviewRewards(private val signals: CampaignSignals, private val reviews: Reviews) : ReviewEventListener {
    override fun onPublished(review: Review, firstTime: Boolean) {
        if (firstTime) signals.report(review.accountId, METRIC, reviews.countWritten(review.accountId).toLong())
    }

    companion object {
        const val METRIC = "reviews.written"
    }
}
