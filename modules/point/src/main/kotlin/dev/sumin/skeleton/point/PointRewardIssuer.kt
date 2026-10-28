package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.reward.IssuedReward
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardLabel
import dev.sumin.skeleton.common.reward.RewardPermanentFailure
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 보상 종류 `point` — params `amount`(필수) · `expiresInDays`(선택, 없으면 안 사라진다. 기본 유효기간은 없다 - 앱이 보상 설정에서 고른다).
 * `(GRANT, grant.id)` 로 적립하므로 일꾼이 같은 건을 두 번 불러도 포인트는 한 번만 쌓인다.
 */
class PointRewardIssuer(private val wallet: PointWallet, private val time: TimeProvider) : RewardIssuer {
    override val type: String = TYPE

    override fun validate(params: Map<String, String>) {
        amountOf(params)
        expiresInDaysOf(params)
    }

    override fun issue(grant: RewardGrantView): IssuedReward {
        val amount = try { amountOf(grant.params).also { expiresInDaysOf(grant.params) } } catch (e: IllegalArgumentException) {
            throw RewardPermanentFailure("Bad point reward settings: ${e.message}", e)   // 설정이 틀리면 다시 해도 같다
        }
        val expiresAt = expiresInDaysOf(grant.params)?.let { time.now().plus(it, ChronoUnit.DAYS) }
        val entry = wallet.earn(grant.accountId, amount, expiresAt, StorePointWallet.GRANT_SOURCE, grant.id)
            ?: wallet.entryOf(StorePointWallet.GRANT_SOURCE, grant.id)
            ?: error("a granted point entry must exist")
        return IssuedReward(entry.id)
    }

    override fun describe(params: Map<String, String>, locale: Locale): RewardLabel {
        val amount = amountOf(params)
        val text = if (locale.language == "ko") "포인트 ${amount}점" else "$amount points"
        val message = if (locale.language == "ko") "${text}을 받았어요." else "You received $text."
        return RewardLabel(title = text, message = message, link = null)
    }

    private fun amountOf(params: Map<String, String>): Long {
        val amount = params["amount"]?.toLongOrNull() ?: throw IllegalArgumentException("amount must be a whole number")
        require(amount in 1..StorePointWallet.MAX_AMOUNT) { "amount must be 1..${StorePointWallet.MAX_AMOUNT}" }
        return amount
    }

    private fun expiresInDaysOf(params: Map<String, String>): Long? {
        val raw = params["expiresInDays"]?.takeIf { it.isNotBlank() } ?: return null
        val days = raw.toLongOrNull() ?: throw IllegalArgumentException("expiresInDays must be a whole number")
        require(days in 1..MAX_DAYS) { "expiresInDays must be 1..$MAX_DAYS" }
        return days
    }

    companion object {
        const val TYPE = "point"
        private const val MAX_DAYS = 36_500L
    }
}
