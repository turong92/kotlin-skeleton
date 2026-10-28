package dev.sumin.skeleton.campaign.jdbc

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.reward.IssuedReward
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardLabel
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.reward.RewardGrants
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

class MutableTime(@Volatile var now: Instant = Instant.parse("2026-10-28T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = now
}

data class RecordedGrant(val accountId: String, val type: String, val params: Map<String, String>, val sourceKey: String)

/** 지급 기록 대역 - 실제 `reward` 처럼 같은 `(sourceType, sourceKey)` 는 한 건으로 접는다. 쿠폰 · 포인트 표는 이 시험에 없다 */
class RecordingGrants : RewardGrants {
    val recorded = CopyOnWriteArrayList<RecordedGrant>()
    private val seen = ConcurrentHashMap.newKeySet<String>()

    override fun record(accountId: String, type: String, params: Map<String, String>, sourceType: String, sourceKey: String): String? {
        if (!seen.add("$sourceType/$sourceKey")) return null
        recorded += RecordedGrant(accountId, type, params, sourceKey)
        return "grant_${recorded.size}"
    }

    fun clear() {
        recorded.clear()
        seen.clear()
    }
}

class FakeFacts : AccountFacts {
    val createdAt = ConcurrentHashMap<String, Instant>()
    override fun createdAt(accountId: String): Instant? = createdAt[accountId]
}

class PointIssuer : RewardIssuer {
    override val type = "point"
    override fun validate(params: Map<String, String>) = require(params["amount"]?.toLongOrNull()?.let { it > 0 } == true) { "amount must be a positive number" }
    override fun issue(grant: RewardGrantView) = IssuedReward("res")
    override fun describe(params: Map<String, String>, locale: Locale) = RewardLabel("Points", "p")
}

@TestConfiguration(proxyBeanMethods = false)
class CampaignDbFixtures {
    @Bean fun time() = MutableTime()
    @Bean fun grants() = RecordingGrants()
    @Bean fun facts() = FakeFacts()
    @Bean fun pointIssuer(): RewardIssuer = PointIssuer()
}
