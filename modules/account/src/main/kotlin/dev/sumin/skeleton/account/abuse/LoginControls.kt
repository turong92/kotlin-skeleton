package dev.sumin.skeleton.account.abuse

import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.Emails
import dev.sumin.skeleton.account.SignInMethods
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.api.AuthErrorCode
import dev.sumin.skeleton.auth.login.LoginAttempt
import dev.sumin.skeleton.auth.login.LoginFailure
import dev.sumin.skeleton.auth.login.LoginHooks
import java.util.concurrent.ConcurrentHashMap

/**
 * 로그인 시도 제한 — 클라이언트 IP 하나와 **입력된 식별자** 하나마다 창 안의 시도 수를 센다.
 * 식별자는 계정이 있든 없든 똑같이 세므로 "이 주소는 잠겼다" 가 계정 존재 여부를 알리지 않는다. 시도 전부를 세는 것(실패만이 아니라)이 의도다:
 * `RateLimitStore` 는 읽기만 하는 연산이 없고, 실패만 세려면 잠김을 따로 저장해야 하는데 그것이 곧 존재 오라클이 된다.
 * 대가는 정상 사용자도 10분에 [dev.sumin.skeleton.account.AccountProperties.Login.perAccount] 번만 시도할 수 있다는 것(공격자가 남의 계정을 잠시 잠글 수 있는 면 — docs/accounts.md).
 * 저장소는 platform 의 `RateLimitStore` (redis-rate-limit 이 있으면 Redis, 아니면 이 인스턴스의 메모리).
 */
class LoginThrottle(private val core: AccountCore) : LoginHooks {
    private val announced = ConcurrentHashMap.newKeySet<String>()

    override fun beforeAttempt(attempt: LoginAttempt) {
        val login = core.props.login
        if (!login.throttleEnabled) return
        attempt.clientIp?.let { check("login:ip", it, login.perIp, "ip", attempt) }
        check("login:id", bucketOf(attempt.identifier), login.perAccount, "identifier", attempt)
    }

    /**
     * 한 계정은 한 버킷 — `email:` · `username:`(이 모듈에서 username 은 이메일이다)은 [Emails.normalize] 한 주소 하나로,
     * `accountId:` 는 그 계정의 주소로 풀어 같은 버킷에 모은다 (입력 방식을 바꿔 한도를 2~3 배로 만들지 못하게).
     * 없는 계정 id 는 자기 이름 그대로 — 결과가 응답에 드러나지 않는다.
     */
    private fun bucketOf(identifier: String): String {
        val kind = identifier.substringBefore(':', "")
        val value = identifier.substringAfter(':', identifier)
        return when (kind) {
            "email", "username" -> Emails.normalize(value)
            "accountId" -> core.accounts.findById(value)?.email ?: "accountId:$value"
            else -> Emails.normalize(identifier)
        }
    }

    private fun check(scope: String, key: String, capacity: Int, label: String, attempt: LoginAttempt) {
        val window = core.props.login.window
        val a = core.limits.acquire(scope, key, capacity, window)
        if (a.allowed) return
        // 창마다 버킷당 첫 거절만 이벤트로 — 공격 중에 감사 표 · 경보가 요청 수만큼 늘지 않게
        val windowIndex = core.time.now().toEpochMilli() / window.toMillis().coerceAtLeast(1)
        if (announced.add("$scope:${AccountRateLimits.digest(key)}@$windowIndex")) {
            if (announced.size > MAX_REMEMBERED) announced.clear()
            core.events.publish(AccountEventType.LOGIN_THROTTLED, null, attempt.clientIp, mapOf("scope" to label, "id" to AccountRateLimits.digest(attempt.identifier)))
        }
        throw RateLimitedException(a.retryAfterSeconds, AuthErrorCode.TOO_MANY_ATTEMPTS)
    }

    private companion object { const val MAX_REMEMBERED = 10_000 }
}

/** 로그인 결과를 계정 이벤트로 · 성공이면 계정 · 수단에 마지막 로그인 시각을 찍는다 */
class LoginRecorder(private val core: AccountCore) : LoginHooks {
    override fun onFailure(attempt: LoginAttempt, reason: LoginFailure, account: AuthAccount?) {
        core.events.publish(
            AccountEventType.LOGIN_FAILURE, account?.accountId, attempt.clientIp,
            mapOf("method" to SignInMethods.PASSWORD, "reason" to reason.name, "id" to AccountRateLimits.digest(attempt.identifier)),
        )
    }

    override fun onSuccess(attempt: LoginAttempt, account: AuthAccount) {
        val now = core.time.now()
        core.accounts.update(account.accountId, dev.sumin.skeleton.account.AccountPatch(lastLoginAt = now), now)
        account.email.takeIf { it.isNotEmpty() }?.let { email -> core.accounts.findIdentity(SignInMethods.PASSWORD, email)?.let { core.accounts.touchIdentity(it.id, now) } }
        core.events.publish(AccountEventType.LOGIN_SUCCESS, account.accountId, attempt.clientIp, mapOf("method" to SignInMethods.PASSWORD))
    }
}
