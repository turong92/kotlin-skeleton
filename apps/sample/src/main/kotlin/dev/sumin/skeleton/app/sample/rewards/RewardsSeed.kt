package dev.sumin.skeleton.app.sample.rewards

import dev.sumin.skeleton.campaign.CampaignInput
import dev.sumin.skeleton.campaign.Campaigns
import dev.sumin.skeleton.coupon.CouponCodes
import dev.sumin.skeleton.coupon.CouponErrorCode
import dev.sumin.skeleton.coupon.CouponException
import dev.sumin.skeleton.coupon.CouponDefinition
import dev.sumin.skeleton.coupon.CouponDefinitionInput
import dev.sumin.skeleton.coupon.CouponDefinitions
import dev.sumin.skeleton.coupon.CouponScopeInput
import dev.sumin.skeleton.coupon.CouponStageInput
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 로컬 프로필의 씨앗(스펙 §10) - 쿠폰 정의 넷(구독용 「첫 달 무료 + 3달 50%」 포함)과 공용 코드, 출석 이벤트(매일 10포인트, 누적 3일 「1,000원 할인」), 친구초대 이벤트(초대받은 사람 「응원 30%」, 초대자 500포인트는
 * 첫 응원 결제 때 - `CheerOrderListener` 가 `qualify(id, "first-payment")` 를 부르고, 청약철회 7일 동안 보류하며 같은 네트워크면 운영자 확인으로 보낸다). 이름으로 찾아 **없는 것만** 만든다 - 다시 떠도 늘지 않고, 운영 화면에서 고친 것을 덮지 않는다.
 * 보상 종류의 선택(포인트 유효기간 없음 · 초대자 보상 시점 `SIGNAL` · 보류 7일 · 같은 네트워크 확인)은 스켈레톤 기본이 아니라 이 앱의 선택이다.
 */
@Component
@Profile("local")
class RewardsSeedRunner(
    private val definitions: CouponDefinitions,
    private val codes: CouponCodes,
    private val campaigns: Campaigns,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = RewardsSeed(definitions, codes, campaigns).run()
}

class RewardsSeed(
    private val definitions: CouponDefinitions,
    private val codes: CouponCodes,
    private val campaigns: Campaigns,
    private val now: () -> Instant = { Instant.now() },
) {
    fun run() {
        val won1000 = definition(WON_1000, listOf(CouponStageInput("FIXED", 1000, null, 1)), currency = "KRW", validDays = 30)
        val cheer30 = definition(CHEER_30, listOf(CouponStageInput("PERCENT", 30, 5000, 1)), validDays = 30)
        definition(FREE_PASS, listOf(CouponStageInput("FREE", periods = 1)), validDays = 30)
        definition(FIRST_MONTH_FREE, listOf(CouponStageInput("FREE", periods = 1), CouponStageInput("PERCENT", 50, null, 3)), validDays = 30, purposes = listOf("SUBSCRIPTION"))
        ensureCode(cheer30)

        val started = now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.MINUTES)
        if (campaigns.list().none { it.name == ATTENDANCE }) {
            campaigns.create(
                "attendance",
                CampaignInput(
                    ATTENDANCE, started, null,
                    mapOf(
                        "daily" to listOf(reward("point", "amount" to "10")),
                        "milestones" to listOf(mapOf("days" to 3, "streak" to false, "rewards" to listOf(reward("coupon", "definitionId" to won1000.id)))),
                    ),
                ),
            )
        }
        if (campaigns.list().none { it.name == REFERRAL }) {
            campaigns.create(
                "referral",
                CampaignInput(
                    REFERRAL, started, null,
                    mapOf(
                        "invitee" to listOf(reward("coupon", "definitionId" to cheer30.id)),
                        "inviter" to listOf(reward("point", "amount" to "500")),
                        "qualifyOn" to "SIGNAL",
                        "qualifyEvent" to "first-payment",
                        "holdDays" to 7,
                        "reviewSameNetwork" to true,
                        "claimWithinDays" to 7,
                        "inviterCap" to 10,
                    ),
                ),
            )
        }
    }

    private fun definition(name: String, stages: List<CouponStageInput>, currency: String? = null, validDays: Int, purposes: List<String> = emptyList()): CouponDefinition =
        definitions.list().firstOrNull { it.name == name }
            ?: definitions.create(CouponDefinitionInput(name = name, stages = stages, currency = currency, scope = CouponScopeInput(purposes = purposes), validDays = validDays, perAccountLimit = 5))

    private fun ensureCode(definition: CouponDefinition) {
        try {
            codes.create(definition.id, WELCOME_CODE)
        } catch (e: CouponException) {
            if (e.couponError != CouponErrorCode.CODE_EXISTS) throw e
        }
    }

    private fun reward(type: String, vararg params: Pair<String, String>) = mapOf("type" to type, "params" to params.toMap())

    companion object {
        const val WON_1000 = "1,000원 할인"
        const val CHEER_30 = "응원 30%"
        const val FREE_PASS = "무료 이용권"
        const val FIRST_MONTH_FREE = "첫 달 무료 + 3달 50%"
        const val WELCOME_CODE = "WELCOME"
        const val ATTENDANCE = "매일 출석"
        const val REFERRAL = "친구 초대"
    }
}
