package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.reward.RewardIssuer

/** 이벤트 설정 안의 보상 한 줄 - [type] 은 지급기 이름(`point` · `coupon`), [params] 는 지급기마다 다른 문자열 값 */
data class RewardSpec(val type: String, val params: Map<String, String>) {
    /**
     * 종류와 params(키 순서 무관)로 정해지는 짧은 이름 - 지급 건 키에 쓴다(SHA-256 앞 16자). 순서나 위치에 기대지 않는다.
     * 값은 정규화한다 - 문자열은 trim, 앞자리 0 이 없는 수(`100` · `100.0`)는 뒤 0 을 뗀 평문 표기로.
     */
    fun identity(): String {
        val canonical = buildString {
            append(type.length).append(':').append(type)
            params.toSortedMap().forEach { (k, v) -> append('|').append(k.length).append(':').append(k).append('=').append(normalize(v).let { it.length.toString() + ":" + it }) }
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    private fun normalize(value: String): String {
        val v = value.trim()
        return if (NUMBER.matches(v)) java.math.BigDecimal(v).stripTrailingZeros().toPlainString() else v
    }

    private companion object {
        val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}

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
    /** 이 이름의 신호만 초대자 보상을 정한다(`qualifyOn: SIGNAL` 일 때만). 없으면 아무 신호나 */
    val qualifyEvent: String? = null,
    /** 초대자 보상을 신호 뒤 이만큼 보류한다(환불 · 취소 기간). 0 이면 바로 */
    val holdDays: Int = 0,
    /** 켜면 [ReferralFlag.SAME_NETWORK] 표시가 붙은 초대는 운영자가 승인할 때까지 지급하지 않는다 */
    val reviewSameNetwork: Boolean = false,
) {
    companion object {
        const val DEFAULT_CLAIM_WITHIN_DAYS = 7
        const val MAX_HOLD_DAYS = 90
        val QUALIFY_EVENT = Regex("[a-z0-9._-]{1,64}")

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
            val event = when (val e = raw["qualifyEvent"]) {
                null -> null
                is String -> e.trim().ifEmpty { null }
                else -> ConfigReader.fail("qualifyEvent must be a string")
            }
            if (event != null) {
                if (!QUALIFY_EVENT.matches(event)) ConfigReader.fail("qualifyEvent must match [a-z0-9._-]{1,64}")
                if (qualifyOn != QualifyOn.SIGNAL) ConfigReader.fail("qualifyEvent is only for qualifyOn SIGNAL")
            }
            val hold = ConfigReader.int(raw["holdDays"], "holdDays") ?: 0
            if (hold !in 0..MAX_HOLD_DAYS) ConfigReader.fail("holdDays must be 0..$MAX_HOLD_DAYS")
            val review = when (val r = raw["reviewSameNetwork"]) {
                null -> false
                is Boolean -> r
                else -> ConfigReader.fail("reviewSameNetwork must be true or false")
            }
            return ReferralConfig(invitee, inviter, qualifyOn, within, cap, event, hold, review)
        }
    }
}

/** 업적 한 단계 - 수치가 [value] 에 닿으면(같거나 넘으면) [rewards] 를 모두 준다 */
data class AchievementStep(val value: Long, val rewards: List<RewardSpec>)

/** 업적 설정 - [metric] 은 앱이 [CampaignSignals.report] 로 보고하는 수치의 이름, 단계는 기준값 오름차순 */
data class AchievementConfig(val metric: String, val milestones: List<AchievementStep>) {
    companion object {
        val METRIC = Regex("[a-z0-9._-]{1,64}")
        const val MAX_STEPS = 20

        fun parse(raw: Map<String, Any?>): AchievementConfig {
            val metric = (raw["metric"] as? String)?.trim().orEmpty()
            if (!METRIC.matches(metric)) ConfigReader.fail("metric must match [a-z0-9._-]{1,64}")
            val steps = ConfigReader.list(raw["milestones"], "milestones").mapIndexed { i, item ->
                val m = ConfigReader.map(item, "milestones[$i]")
                val value = ConfigReader.long(m["value"], "milestones[$i].value") ?: ConfigReader.fail("milestones[$i].value is required")
                if (value < 1) ConfigReader.fail("milestones[$i].value must be at least 1")
                val rewards = ConfigReader.rewards(m["rewards"], "milestones[$i].rewards")
                if (rewards.isEmpty()) ConfigReader.fail("milestones[$i].rewards must not be empty")
                AchievementStep(value, rewards)
            }
            if (steps.isEmpty()) ConfigReader.fail("an achievement campaign needs at least one milestone")
            if (steps.size > MAX_STEPS) ConfigReader.fail("an achievement campaign has at most $MAX_STEPS milestones")
            steps.zipWithNext().forEachIndexed { i, (a, b) -> if (b.value <= a.value) ConfigReader.fail("milestones[${i + 1}].value must be greater than the previous one") }
            return AchievementConfig(metric, steps)
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

    fun long(raw: Any?, path: String): Long? = when (raw) {
        null -> null
        is Int -> raw.toLong()
        is Long -> raw
        is Number -> if (raw.toDouble() % 1.0 == 0.0) raw.toLong() else fail("$path must be a whole number")
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
