package dev.sumin.skeleton.app.sample.reviews

import dev.sumin.skeleton.badge.BadgeDefinitionInput
import dev.sumin.skeleton.badge.BadgeDefinitions
import dev.sumin.skeleton.campaign.CampaignInput
import dev.sumin.skeleton.campaign.Campaigns
import dev.sumin.skeleton.review.ReviewPurchases
import dev.sumin.skeleton.review.ReviewTarget
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 로컬 프로필의 씨앗 - ① 시드 계정 `acc_user` 의 `product:basic` 구매 줄(결제 키가 없는 로컬에서도 리뷰를 써 볼 수 있게 - 견본은 산 사람만 쓴다),
 * ② 배지 「첫 리뷰」, ③ 업적 이벤트 하나(수치 `reviews.written`: 1 → 배지 「첫 리뷰」 + 100포인트). 없는 것만 만든다 - 다시 떠도 늘지 않는다
 * (구매 줄은 `ref` 가 같아 한 줄, 정의 · 이벤트는 코드 · 이름으로 찾는다).
 */
@Component
@Profile("local")
class ReviewsSeedRunner(
    private val purchases: ReviewPurchases,
    private val definitions: BadgeDefinitions,
    private val campaigns: Campaigns,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = ReviewsSeed(purchases, definitions, campaigns).run()
}

class ReviewsSeed(
    private val purchases: ReviewPurchases,
    private val definitions: BadgeDefinitions,
    private val campaigns: Campaigns,
    private val now: () -> Instant = { Instant.now() },
) {
    fun run() {
        purchases.record(SEED_ACCOUNT, ReviewTarget(ReviewPurchaseSignals.TARGET_TYPE, "basic"), "seed:$SEED_ACCOUNT:basic")
        if (definitions.findByCode(FIRST_REVIEW) == null) definitions.create(BadgeDefinitionInput(FIRST_REVIEW, "첫 리뷰", "리뷰를 처음 남겼어요", null, false, 5))
        if (campaigns.list().none { it.name == REVIEWS_ACHIEVEMENT }) {
            campaigns.create(
                "achievement",
                CampaignInput(
                    REVIEWS_ACHIEVEMENT, now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.MINUTES), null,
                    mapOf(
                        "metric" to ReviewRewards.METRIC,
                        "milestones" to listOf(mapOf("value" to 1, "rewards" to listOf(reward("badge", "code" to FIRST_REVIEW), reward("point", "amount" to "100")))),
                    ),
                ),
            )
        }
    }

    private fun reward(type: String, vararg params: Pair<String, String>) = mapOf("type" to type, "params" to params.toMap())

    companion object {
        const val SEED_ACCOUNT = "acc_user"
        const val FIRST_REVIEW = "first-review"
        const val REVIEWS_ACHIEVEMENT = "리뷰 업적"
    }
}
