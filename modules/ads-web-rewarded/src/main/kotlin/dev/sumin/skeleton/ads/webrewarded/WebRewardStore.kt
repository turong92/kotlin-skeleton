package dev.sumin.skeleton.ads.webrewarded

import java.time.Instant

/** 세션 · 하루 카운터 저장 포트 — 구현은 `ads-web-rewarded-jdbc`, 다른 DB 는 앱이 채운다 */
interface WebRewardStore {
    /** [block] 을 한 트랜잭션으로. 예외가 나면 안에서 한 쓰기는 전부 되돌아간다 */
    fun <T> inTransaction(block: () -> T): T

    fun insert(session: WebRewardSession)

    /** 세션을 잠그고(`for update`) 읽는다 — 없거나 남의 것이면 null. [inTransaction] 안에서 */
    fun lockOwned(sessionId: String, accountId: String): WebRewardSession?

    /** [dayStart] 하루에 이 대상([subject])이 받은 보상 수(읽기만) */
    fun grantedOn(scope: WebRewardScope, subject: String, dayStart: Instant): Long

    /** 이 하루의 카운터가 [max] 미만일 때만 1 올리고 true — 원자적이다(동시 요청이 max 를 못 넘긴다) */
    fun incrementWithin(scope: WebRewardScope, subject: String, dayStart: Instant, max: Long): Boolean

    /** 세션을 보상됨으로 — 지급 결과([rewardRef]) 또는 토큰 해시 · 만료를 남긴다 */
    fun markGranted(sessionId: String, grantedAt: Instant, rewardType: String?, rewardRef: String?, tokenHash: String?, tokenExpiresAt: Instant?)

    /** 토큰을 한 번만 쓴다 — 해시 · 계정 · 목적이 맞고 아직 안 쓰였고 만료 전일 때만 세션을 돌려준다(원자적 1회) */
    fun redeem(tokenHash: String, accountId: String, purpose: String, now: Instant): WebRewardSession?

    /**
     * [cutoff] 보다 오래된 줄을 세션 · 카운터 합쳐 지운다(각각 최대 [limit] 줄) — 지운 줄 수. 세션은 `opened_at < cutoff` 이고 **아직 쓸 수 있는 토큰이 없는** 줄만
     * (`token_expires_at` 이 없거나 `< cutoff`), 카운터는 `day_start < cutoff` 이다. 멱등이라 여러 인스턴스가 겹쳐도 안전하다
     */
    fun purgeBefore(cutoff: Instant, limit: Int): Int

    /** 계정의 세션 줄과 계정 카운터를 지운다(IP 카운터는 남는다) */
    fun eraseAccount(accountId: String)
}
