package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException

/**
 * 실시간 아이디 확인의 답. [reason] 은 `INVALID`(형식 — [code] 가 `Required` · `Size` · `Pattern`) 또는 `TAKEN`(예약어 · 살아 있는 · 탈퇴한 아이디를 가르지 않는다).
 */
data class LoginIdAvailability(val normalized: String, val available: Boolean, val reason: String? = null, val code: String? = null) {
    override fun toString() = "LoginIdAvailability(normalized=<redacted>, available=$available, reason=$reason, code=$code)"
}

/**
 * 입력하는 즉시 아이디를 쓸 수 있는지 알린다 (`POST /api/v1/account/login-id/availability`). HTTP 변환은 컨트롤러, 규칙은 여기.
 * - 형식(`LoginIds`)은 요청 본문만 본다 — 계정 저장소를 읽지 않고 **IP 한도를 쓰지 않는다** (가입의 형식 검사와 같은 자리).
 * - 쓸 수 있나는 가입 3번과 **같은 함수**([AccountCore.loginIdAvailable])다. 이유를 가르지 않는다.
 * - 한 IP 가 `check-per-ip-window` 에 `check-per-ip` 번 — 넘으면 [RateLimitedException]. 후보 아이디는 이벤트 · 감사에 남기지 않는다.
 * "쓸 수 있음"은 예약이 아니다 — 가입이 다시 판정한다.
 */
class LoginIdAvailabilityService(private val core: AccountCore) {
    fun check(raw: String?, ipKey: String?): LoginIdAvailability {
        val cfg = core.props.loginId
        val normalized = LoginIds.normalize(raw.orEmpty())
        LoginIds.problemOf(normalized, cfg.minLength, cfg.maxLength)?.let { return LoginIdAvailability(LoginIds.clip(normalized), false, "INVALID", it.code) }
        ipKey?.let {
            val a = core.limits.acquire("login-id-check:ip", it, cfg.checkPerIp, cfg.checkPerIpWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        return if (core.loginIdAvailable(normalized)) LoginIdAvailability(normalized, true) else LoginIdAvailability(normalized, false, "TAKEN")
    }
}
