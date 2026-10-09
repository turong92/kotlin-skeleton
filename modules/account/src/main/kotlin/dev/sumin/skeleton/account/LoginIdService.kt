package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType

/**
 * 「아이디 정하기」 (`POST /api/v1/account/login-id`, 스펙 §8.1) — 아이디가 없는 계정(소셜 첫 로그인 · 매직 링크 · 켜기 전부터 있던 계정)이 처음 한 번 정한다. HTTP 변환은 컨트롤러, 규칙은 여기.
 *  - 형식은 가입과 같은 한 곳([AccountCore.acceptLoginId])이 본다. 형식 오류는 한도를 쓰지 않는다 (입력 중인 사람을 잠그지 않는다).
 *  - 형식을 통과한 요청은 결과와 무관하게 **시도**로 센다 (`claim-limit` · `claim-window`) — 계정별로 하나, **IP 별로 하나**(`login-id-claim:ip`, 같은 값 · 버킷만 따로). 결과를 이용해 남의 아이디를 더듬는 일을 늦추고,
 *    한 소셜 신원이 아이디를 정하고 → 계정을 버리고 → 새 계정으로 다시 정하며 아이디를 영구 소각하는 반복(해시는 지워지지 않는다)도 IP 한도가 막는다. 같은 `429 ACCOUNT.RATE_LIMITED`.
 *  - 예약어는 저장소가 모르므로 여기서 `TAKEN` 으로 거절한다. 살아 있는 · 탈퇴한 아이디는 저장소 `claimLoginId` 가 같은 `TAKEN` 으로 답한다 (둘을 가르지 않는다).
 *  - 이메일이 없어도 된다. 재인증은 없다 (처음 정하는 온보딩이고 아이디만으로는 로그인되지 않는다). 이벤트 `LOGIN_ID_SET` 의 detail 은 `via=gate` 뿐 — 아이디 값은 싣지 않는다.
 */
class LoginIdService(private val core: AccountCore) {
    fun claim(accountId: String, raw: String?, ip: String?, ipKey: String? = ip) {
        val cfg = core.props.loginId
        val normalized = core.acceptLoginId(raw)
        val attempt = core.limits.acquire("login-id-claim:account", accountId, cfg.claimLimit, cfg.claimWindow)
        if (!attempt.allowed) throw RateLimitedException(attempt.retryAfterSeconds)
        // 같은 IP 의 시도도 센다 — 계정별 한도만 있으면 소셜로 새 계정을 만들 때마다(버리기 → 다시 로그인) 한도가 새로 열려 아이디를 무제한 소각한다. 값은 계정 한도와 같다 (버킷만 따로)
        ipKey?.let {
            val byIp = core.limits.acquire("login-id-claim:ip", it, cfg.claimLimit, cfg.claimWindow)
            if (!byIp.allowed) throw RateLimitedException(byIp.retryAfterSeconds)
        }
        if (LoginIds.isReserved(normalized, cfg.reserved)) throw AccountException(AccountErrorCode.LOGIN_ID_TAKEN)
        when (core.accounts.claimLoginId(accountId, normalized, core.loginIdHashOf(normalized)!!, core.time.now())) {
            ClaimLoginIdResult.CLAIMED -> core.events.publish(AccountEventType.LOGIN_ID_SET, accountId, ip, mapOf("via" to "gate"))
            ClaimLoginIdResult.TAKEN -> throw AccountException(AccountErrorCode.LOGIN_ID_TAKEN)
            ClaimLoginIdResult.ALREADY_SET -> throw AccountException(AccountErrorCode.LOGIN_ID_ALREADY_SET)
            ClaimLoginIdResult.NOT_ACTIVE -> throw AccountException(AccountErrorCode.NOT_ACTIVE)
            ClaimLoginIdResult.NOT_FOUND -> throw AccountException(AccountErrorCode.NOT_FOUND)
        }
    }
}
