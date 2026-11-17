package dev.sumin.skeleton.ads.webrewarded

import java.time.Instant

/** 광고 세션 한 줄 — 열 때 만들고 보상 요청이 한 번 [grantedAt] 을 채운다. 토큰 해시는 저장소에만 있고 여기엔 없다 */
data class WebRewardSession(
    val id: String,
    val accountId: String,
    val purpose: String,
    /** IP 상한의 키(`ClientIps.limitKey`) */
    val clientKey: String,
    val openedAt: Instant,
    val expiresAt: Instant,
    val grantedAt: Instant?,
    val rewardType: String?,
    /** 지급기가 돌려준 결과 id(포인트 내역 id …) — 토큰 모드면 null */
    val rewardRef: String?,
    val tokenExpiresAt: Instant?,
    val redeemedAt: Instant?,
)

/** 상한을 세는 단위 */
enum class WebRewardScope { ACCOUNT, IP }

/** 보상 요청의 결과. [token] 은 토큰 모드에서만이고 원문은 이 한 번만 돌려준다(저장은 해시) */
data class WebRewardGranted(val session: WebRewardSession, val token: String?) {
    override fun toString() = "WebRewardGranted(session=$session, token=${if (token == null) "null" else "***"})"
}
