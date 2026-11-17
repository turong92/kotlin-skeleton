package dev.sumin.skeleton.adswebtest

import dev.sumin.skeleton.ads.webrewarded.WebRewardScope
import dev.sumin.skeleton.ads.webrewarded.WebRewardSession
import dev.sumin.skeleton.ads.webrewarded.WebRewardStore
import dev.sumin.skeleton.common.reward.IssuedReward
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardLabel
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.Locale
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.authentication.TestingAuthenticationToken

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class WebRewardTestApplication

fun account(id: String) = TestingAuthenticationToken(id, "n/a", "ROLE_USER")

@TestConfiguration(proxyBeanMethods = false)
class WebRewardFakes {
    @Bean fun fakeWebRewardStore() = FakeWebRewardStore()

    @Bean fun recordingIssuer() = RecordingIssuer()

    @Bean fun mutableClock() = MutableClock()
}

/** 움직이는 고정 시계 */
class MutableClock(var now: Instant = Instant.parse("2026-11-18T03:00:00Z")) : TimeProvider {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** 부른 것을 기록하는 `point` 지급기 — [failure] 가 있으면 던진다 */
class RecordingIssuer(override val type: String = "point") : RewardIssuer {
    val issued = mutableListOf<RewardGrantView>()
    var failure: RuntimeException? = null

    override fun validate(params: Map<String, String>) {
        require(params["amount"]?.toLongOrNull()?.let { it > 0 } == true) { "amount is required" }
    }

    override fun issue(grant: RewardGrantView): IssuedReward {
        failure?.let { throw it }
        issued += grant
        return IssuedReward("pt_${issued.size}")
    }

    override fun describe(params: Map<String, String>, locale: Locale) = RewardLabel("t", "m")
}

/** 메모리 저장소 — 트랜잭션은 스냅샷을 떠 두었다가 예외가 나면 되돌린다 */
class FakeWebRewardStore : WebRewardStore {
    val sessions = linkedMapOf<String, WebRewardSession>()
    val tokens = mutableMapOf<String, String>()   // 해시 → 세션 id
    val counts = mutableMapOf<Triple<WebRewardScope, String, Instant>, Long>()

    override fun <T> inTransaction(block: () -> T): T {
        val s = LinkedHashMap(sessions)
        val t = HashMap(tokens)
        val c = HashMap(counts)
        try {
            return block()
        } catch (e: Throwable) {
            sessions.clear(); sessions.putAll(s)
            tokens.clear(); tokens.putAll(t)
            counts.clear(); counts.putAll(c)
            throw e
        }
    }

    override fun insert(session: WebRewardSession) {
        sessions[session.id] = session
    }

    override fun lockOwned(sessionId: String, accountId: String) = sessions[sessionId]?.takeIf { it.accountId == accountId }

    override fun grantedOn(scope: WebRewardScope, subject: String, dayStart: Instant) = counts[Triple(scope, subject, dayStart)] ?: 0L

    override fun incrementWithin(scope: WebRewardScope, subject: String, dayStart: Instant, max: Long): Boolean {
        val key = Triple(scope, subject, dayStart)
        if ((counts[key] ?: 0L) >= max) return false
        counts[key] = (counts[key] ?: 0L) + 1
        return true
    }

    override fun markGranted(sessionId: String, grantedAt: Instant, rewardType: String?, rewardRef: String?, tokenHash: String?, tokenExpiresAt: Instant?) {
        sessions[sessionId] = sessions.getValue(sessionId).copy(grantedAt = grantedAt, rewardType = rewardType, rewardRef = rewardRef, tokenExpiresAt = tokenExpiresAt)
        if (tokenHash != null) tokens[tokenHash] = sessionId
    }

    override fun redeem(tokenHash: String, accountId: String, purpose: String, now: Instant): WebRewardSession? {
        val s = tokens[tokenHash]?.let { sessions[it] } ?: return null
        if (s.accountId != accountId || s.purpose != purpose || s.redeemedAt != null || s.tokenExpiresAt?.isAfter(now) != true) return null
        return s.copy(redeemedAt = now).also { sessions[s.id] = it }
    }

    override fun purgeBefore(cutoff: Instant, limit: Int): Int {
        val ids = sessions.values.filter { it.openedAt.isBefore(cutoff) && (it.tokenExpiresAt == null || it.tokenExpiresAt.isBefore(cutoff)) }.take(limit).map { it.id }
        ids.forEach { sessions.remove(it) }
        val keys = counts.keys.filter { it.third.isBefore(cutoff) }.take(limit)
        keys.forEach { counts.remove(it) }
        return ids.size + keys.size
    }

    override fun eraseAccount(accountId: String) {
        sessions.values.removeIf { it.accountId == accountId }
        counts.keys.removeIf { it.first == WebRewardScope.ACCOUNT && it.second == accountId }
    }
}
