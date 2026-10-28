package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.reward.RewardIssuer

/** 이벤트 설정 안의 보상 한 줄 - [type] 은 지급기 이름(`point` · `coupon`), [params] 는 지급기마다 다른 문자열 값 */
data class RewardSpec(val type: String, val params: Map<String, String>)

data class AttendanceMilestone(val days: Int, val streak: Boolean, val rewards: List<RewardSpec>)

data class AttendanceConfig(val daily: List<RewardSpec>, val milestones: List<AttendanceMilestone>) {
    companion object {
        fun parse(raw: Map<String, Any?>): AttendanceConfig {
            val daily = ConfigReader.rewards(raw["daily"], "daily")
            val milestones = ConfigReader.list(raw["milestones"], "milestones").mapIndexed { i, item ->
                val m = ConfigReader.map(item, "milestones[$i]")
                val days = ConfigReader.int(m["days"], "milestones[$i].days") ?: ConfigReader.fail("milestones[$i].days is required")
                if (days !in 1..3650) ConfigReader.fail("milestones[$i].days must be 1..3650")
                val streak = (m["streak"] as? Boolean) ?: false
                val rewards = ConfigReader.rewards(m["rewards"], "milestones[$i].rewards")
                if (rewards.isEmpty()) ConfigReader.fail("milestones[$i].rewards must not be empty")
                AttendanceMilestone(days, streak, rewards)
            }
            if (daily.isEmpty() && milestones.isEmpty()) ConfigReader.fail("an attendance campaign needs daily rewards or milestones")
            return AttendanceConfig(daily, milestones)
        }
    }
}

/** 초대자 보상 시점 - `CLAIM`: 초대받은 사람이 코드를 쓰는 즉시, `SIGNAL`: 앱이 [Referrals.qualify] 를 부를 때(예: 첫 결제) */
enum class QualifyOn { CLAIM, SIGNAL }

data class ReferralConfig(
    val invitee: List<RewardSpec>,
    val inviter: List<RewardSpec>,
    val qualifyOn: QualifyOn,
    val claimWithinDays: Int,
    val inviterCap: Int?,
) {
    companion object {
        const val DEFAULT_CLAIM_WITHIN_DAYS = 7

        fun parse(raw: Map<String, Any?>): ReferralConfig {
            val invitee = ConfigReader.rewards(raw["invitee"], "invitee")
            val inviter = ConfigReader.rewards(raw["inviter"], "inviter")
            if (invitee.isEmpty() && inviter.isEmpty()) ConfigReader.fail("a referral campaign needs invitee or inviter rewards")
            // 스켈레톤은 초대자 보상 시점의 기본값을 정하지 않는다 - 설정이 정해야 한다
            val qualifyOn = (raw["qualifyOn"] as? String)?.let { v -> QualifyOn.entries.firstOrNull { it.name == v } }
                ?: ConfigReader.fail("qualifyOn is required: CLAIM or SIGNAL")
            val within = ConfigReader.int(raw["claimWithinDays"], "claimWithinDays") ?: DEFAULT_CLAIM_WITHIN_DAYS
            if (within !in 1..365) ConfigReader.fail("claimWithinDays must be 1..365")
            val cap = ConfigReader.int(raw["inviterCap"], "inviterCap")
            if (cap != null && cap < 1) ConfigReader.fail("inviterCap must be at least 1")
            return ReferralConfig(invitee, inviter, qualifyOn, within, cap)
        }
    }
}

internal object ConfigReader {
    fun fail(message: String): Nothing = throw CampaignException(CampaignErrorCode.CONFIG_INVALID, message)

    fun list(raw: Any?, path: String): List<Any?> = when (raw) {
        null -> emptyList()
        is List<*> -> raw
        else -> fail("$path must be a list")
    }

    @Suppress("UNCHECKED_CAST")
    fun map(raw: Any?, path: String): Map<String, Any?> = (raw as? Map<String, Any?>) ?: fail("$path must be an object")

    fun int(raw: Any?, path: String): Int? = when (raw) {
        null -> null
        is Int -> raw
        is Long -> raw.toInt()
        is Number -> if (raw.toDouble() % 1.0 == 0.0) raw.toInt() else fail("$path must be a whole number")
        else -> fail("$path must be a number")
    }

    fun rewards(raw: Any?, path: String): List<RewardSpec> = list(raw, path).mapIndexed { i, item ->
        val m = map(item, "$path[$i]")
        val type = (m["type"] as? String)?.takeIf { it.isNotBlank() } ?: fail("$path[$i].type is required")
        val params = when (val p = m["params"]) {
            null -> emptyMap()
            is Map<*, *> -> p.entries.associate { (k, v) -> k.toString() to (v?.toString() ?: fail("$path[$i].params.$k must not be null")) }
            else -> fail("$path[$i].params must be an object")
        }
        RewardSpec(type, params)
    }

    /** 보상 종류를 아는 지급기가 있고, 지급기가 params 를 받아들이는지 본다 */
    fun checkRewards(specs: List<RewardSpec>, issuers: Map<String, RewardIssuer>) {
        specs.forEach { spec ->
            val issuer = issuers[spec.type] ?: fail("unknown reward type '${spec.type}'")
            try {
                issuer.validate(spec.params)
            } catch (e: IllegalArgumentException) {
                fail("reward '${spec.type}': ${e.message}")
            }
        }
    }
}
