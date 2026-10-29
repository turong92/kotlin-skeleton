package dev.sumin.skeleton.badge

import dev.sumin.skeleton.common.reward.IssuedReward
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardLabel
import dev.sumin.skeleton.common.reward.RewardPermanentFailure
import java.util.Locale

/**
 * 보상 종류 `badge` — params `code` 의 배지를 준다. `(GRANT, 지급 건 id)` 로 멱등이라 일꾼이 두 번 불러도 한 번이다.
 * 배지가 없거나 보관됐으면 다시 해도 같으므로 [RewardPermanentFailure] 다(운영자가 풀고 「다시 지급」).
 * 이 클래스는 `platform` 의 계약만 구현한다 - `reward` · `campaign` 을 모른다.
 */
class BadgeRewardIssuer(
    private val definitions: BadgeDefinitions,
    private val awards: BadgeAwards,
) : RewardIssuer {
    override val type: String = TYPE

    override fun validate(params: Map<String, String>) {
        val code = params[CODE]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("badge reward needs params.$CODE")
        val definition = requireNotNull(definitions.findByCode(code)) { "unknown badge '$code'" }
        require(definition.status == BadgeStatus.ACTIVE) { "badge '$code' is archived" }
    }

    override fun issue(grant: RewardGrantView): IssuedReward {
        val code = grant.params[CODE]?.takeIf { it.isNotBlank() } ?: throw RewardPermanentFailure("badge grant ${grant.id} has no $CODE")
        val holding = try {
            awards.awardForGrant(grant.accountId, code, grant.id)
        } catch (e: BadgeException) {
            throw RewardPermanentFailure(e.message ?: e.badgeError.title, e)
        }
        return IssuedReward(holding.awardId)
    }

    override fun describe(params: Map<String, String>, locale: Locale): RewardLabel {
        val name = params[CODE]?.let { runCatching { definitions.findByCode(it)?.name }.getOrNull() }
        val korean = locale.language == "ko"
        return RewardLabel(
            title = name ?: if (korean) "배지" else "Badge",
            message = if (korean) "배지를 받았어요" else "You earned a badge",
            link = LINK,
        )
    }

    companion object {
        const val TYPE = "badge"
        const val CODE = "code"
        const val LINK = "/rewards/badges"
    }
}
