package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.reward.IssuedReward
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardLabel
import dev.sumin.skeleton.common.reward.RewardPermanentFailure
import java.util.Locale

/**
 * 보상 종류 `coupon` — params `definitionId` 의 정의로 쿠폰 한 장을 쿠폰함에 넣는다. `(GRANT, 지급 건 id)` 로 멱등이라 일꾼이 두 번 불러도 한 장이다.
 * 정의가 없거나 발급 중이 아니거나 한도에 막히면 다시 해도 같으므로 [RewardPermanentFailure] 다(운영자가 풀고 「다시 지급」).
 */
class CouponRewardIssuer(
    private val definitions: CouponDefinitions,
    private val issuance: CouponIssuance,
) : RewardIssuer {
    override val type: String = TYPE

    override fun validate(params: Map<String, String>) {
        val id = params[DEFINITION_ID]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("coupon reward needs params.$DEFINITION_ID")
        requireNotNull(definitions.find(id)) { "unknown coupon definition '$id'" }
    }

    override fun issue(grant: RewardGrantView): IssuedReward {
        val definitionId = grant.params[DEFINITION_ID]?.takeIf { it.isNotBlank() } ?: throw RewardPermanentFailure("coupon grant ${grant.id} has no $DEFINITION_ID")
        val coupon = try {
            issuance.issue(definitionId, grant.accountId, CouponSource.GRANT, grant.id)
        } catch (e: CouponException) {
            throw RewardPermanentFailure(e.message ?: e.couponError.title, e)
        } ?: throw RewardPermanentFailure("coupon limit reached for definition $definitionId")
        return IssuedReward(coupon.id)
    }

    override fun describe(params: Map<String, String>, locale: Locale): RewardLabel {
        val name = params[DEFINITION_ID]?.let { runCatching { definitions.find(it)?.name }.getOrNull() }
        val korean = locale.language == "ko"
        return RewardLabel(
            title = name ?: if (korean) "쿠폰" else "Coupon",
            message = if (korean) "쿠폰이 도착했어요" else "A coupon has arrived",
            link = LINK,
        )
    }

    companion object {
        const val TYPE = "coupon"
        const val DEFINITION_ID = "definitionId"
        const val LINK = "/rewards/coupons"
    }
}
