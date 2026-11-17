package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.DayWindow
import dev.sumin.skeleton.common.time.TimeProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import org.springframework.dao.DataAccessException

/** 토큰 모드의 토큰을 쓰는 쪽 — 앱이 자기 엔드포인트에서 부른다. 쓸 수 없는 토큰(없음 · 남의 것 · 다른 목적 · 이미 씀 · 만료)이면 null */
fun interface WebRewardTokens {
    fun redeem(token: String, accountId: String, purpose: String): WebRewardSession?
}

/**
 * 광고 세션 열기와 보상 요청. 위조를 다 막을 수는 없어서(보상의 근거가 브라우저 신호뿐) 1회용 세션 · 최소 경과 시간 · 계정/IP 하루 상한으로 남용을 막는다.
 * 에러는 모두 트랜잭션 밖에서 던진다 — 안에서는 결과 값을 돌려주고, 되돌려야 하는 실패(상한)만 내부 예외로 롤백한 뒤 밖에서 바꾼다.
 */
class WebRewardService(
    private val store: WebRewardStore,
    private val properties: WebRewardedProperties,
    private val issuer: RewardIssuer?,
    private val time: TimeProvider,
    private val ids: SortableIds = SortableIds(),
) : WebRewardTokens {
    private val zone = ZoneId.of(properties.dayZone)
    private val random = SecureRandom()

    init {
        properties.rewardType?.let { type ->
            checkNotNull(issuer?.takeIf { it.type == type }) { "skeleton.ads-web-rewarded.reward-type=$type but no RewardIssuer bean of that type" }
                .validate(properties.rewardParams)
        }
    }

    /** 세션을 연다 — 상한이 이미 찼으면 광고를 띄우기 전에 [WebRewardedErrorCode.DAILY_LIMIT] (읽기만 한다. 진짜 상한은 [grant] 에서 센다) */
    fun open(accountId: String, purpose: String, clientKey: String): WebRewardSession {
        val now = time.now()
        val day = DayWindow.containing(now, zone).from
        val full = (properties.accountDailyLimit?.let { store.grantedOn(WebRewardScope.ACCOUNT, accountId, day) >= it } ?: false) ||
            (properties.ipDailyLimit?.let { store.grantedOn(WebRewardScope.IP, clientKey, day) >= it } ?: false)
        if (full) throw WebRewardedException(WebRewardedErrorCode.DAILY_LIMIT)
        val session = WebRewardSession(
            id = ids.newId("wrs_"), accountId = accountId, purpose = purpose, clientKey = clientKey, openedAt = now, expiresAt = now.plus(properties.sessionTtl),
            grantedAt = null, rewardType = null, rewardRef = null, tokenExpiresAt = null, redeemedAt = null,
        )
        try {
            store.inTransaction { store.insert(session) }
        } catch (e: DataAccessException) {
            throw WebRewardedException(WebRewardedErrorCode.REWARD_UNAVAILABLE, e)
        }
        return session
    }

    fun grant(accountId: String, sessionId: String): WebRewardGranted {
        val outcome = try {
            store.inTransaction { grantInTransaction(accountId, sessionId) }
        } catch (e: LimitReached) {
            WebRewardedErrorCode.DAILY_LIMIT
        } catch (e: DataAccessException) {
            throw WebRewardedException(WebRewardedErrorCode.REWARD_UNAVAILABLE, e)
        } catch (e: RuntimeException) {
            // 지급기가 던졌다 — 트랜잭션은 이미 되돌아갔다(세션은 열린 채, 카운터는 그대로)
            throw WebRewardedException(WebRewardedErrorCode.REWARD_UNAVAILABLE, e)
        }
        return when (outcome) {
            is WebRewardedErrorCode -> throw WebRewardedException(outcome)
            is WebRewardGranted -> outcome
            else -> error("unreachable")
        }
    }

    override fun redeem(token: String, accountId: String, purpose: String): WebRewardSession? =
        store.inTransaction { store.redeem(hash(token), accountId, purpose, time.now()) }

    /** 결과 값(에러 코드 또는 [WebRewardGranted])을 돌려준다. 상한만 롤백이 필요해 [LimitReached] 로 던진다 */
    private fun grantInTransaction(accountId: String, sessionId: String): Any {
        val now = time.now()
        val session = store.lockOwned(sessionId, accountId) ?: return WebRewardedErrorCode.SESSION_NOT_FOUND
        if (session.grantedAt != null) return WebRewardedErrorCode.SESSION_USED
        if (!now.isBefore(session.expiresAt)) return WebRewardedErrorCode.SESSION_EXPIRED
        if (now.isBefore(session.openedAt.plus(properties.minElapsed))) return WebRewardedErrorCode.TOO_EARLY

        val day = DayWindow.containing(now, zone).from
        properties.accountDailyLimit?.let { if (!store.incrementWithin(WebRewardScope.ACCOUNT, accountId, day, it)) throw LimitReached() }
        properties.ipDailyLimit?.let { if (!store.incrementWithin(WebRewardScope.IP, session.clientKey, day, it)) throw LimitReached() }

        val type = properties.rewardType
        var ref: String? = null
        var token: String? = null
        var tokenHash: String? = null
        var tokenExpiresAt: Instant? = null
        if (type != null) {
            ref = issuer!!.issue(RewardGrantView(session.id, accountId, type, properties.rewardParams, SOURCE_TYPE, session.id)).resultRef
        } else {
            token = newToken()
            tokenHash = hash(token)
            tokenExpiresAt = now.plus(properties.tokenTtl)
        }
        store.markGranted(session.id, now, type, ref, tokenHash, tokenExpiresAt)
        return WebRewardGranted(session.copy(grantedAt = now, rewardType = type, rewardRef = ref, tokenExpiresAt = tokenExpiresAt), token)
    }

    private fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private class LimitReached : RuntimeException(null, null, false, false)

    companion object {
        const val SOURCE_TYPE = "ADS_WEB_REWARDED"
    }
}
