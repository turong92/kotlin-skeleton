package dev.sumin.skeleton.ads.webrewarded.web

import dev.sumin.skeleton.ads.webrewarded.WebRewardGranted
import dev.sumin.skeleton.ads.webrewarded.WebRewardSession
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant

/** 이 광고가 무엇을 위한 것인지(앱이 정한 이름) — 토큰을 쓸 때 같은 목적인지 맞춰 본다 */
data class OpenSessionRequest(
    @field:NotNull @field:Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") val purpose: String?,
)

data class WebRewardSessionResponse(val sessionId: String, val purpose: String, val expiresAt: Instant) {
    companion object {
        fun of(session: WebRewardSession) = WebRewardSessionResponse(session.id, session.purpose, session.expiresAt)
    }
}

data class RewardRef(val type: String, val ref: String)

/** 보상 요청 결과 — `reward` 모드면 [reward] 만, 토큰 모드면 [token](1회용)과 [tokenExpiresAt] 만 차 있다 */
data class WebRewardGrantResponse(
    val sessionId: String,
    val purpose: String,
    val grantedAt: Instant,
    val reward: RewardRef?,
    val token: String?,
    val tokenExpiresAt: Instant?,
) {
    override fun toString() = "WebRewardGrantResponse(sessionId=$sessionId, purpose=$purpose, grantedAt=$grantedAt, reward=$reward, token=${if (token == null) "null" else "***"}, tokenExpiresAt=$tokenExpiresAt)"

    companion object {
        fun of(granted: WebRewardGranted): WebRewardGrantResponse {
            val s = granted.session
            return WebRewardGrantResponse(
                s.id, s.purpose, s.grantedAt!!,
                reward = if (s.rewardType != null && s.rewardRef != null) RewardRef(s.rewardType, s.rewardRef) else null,
                token = granted.token,
                tokenExpiresAt = if (granted.token != null) s.tokenExpiresAt else null,
            )
        }
    }
}
