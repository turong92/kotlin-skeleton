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
        attempt.limitKey?.let { check("login:ip", it, login.perIp, "ip", attempt) }
        check("login:id", bucketOf(attempt.identifier), login.perAccount, "identifier", attempt)
    }

    /**
     * 입력 방식마다 버킷이 있다 — `email:` 은 [Emails.normalize] 한 주소, `username:`(켜짐 + `@` 없음 → 아이디)은 **계정이 있든 없든** 정규화한 입력 자기 이름(`loginId:<입력>`),
     * `accountId:` 는 그 계정의 주소로 풀어 이메일 버킷에 모은다 (없는 계정 id 는 자기 이름 그대로).
     * 아이디를 계정의 이메일 버킷으로 합치지 않는 까닭: 합치면 "이메일 버킷을 채운 뒤 후보 아이디가 429 면 그 이메일 계정의 아이디" 로 아이디 ↔ 이메일을 확인해 준다.
     * 아이디 버킷은 저장소를 읽지 않으므로 존재 여부로 일이 갈리지 않는다 (타이밍도 같다). 대가: 한 계정의 추측이 창마다 이메일 몫 + 아이디 몫이다.
     */
    private fun bucketOf(identifier: String): String {
        val kind = identifier.substringBefore(':', "")
        val value = identifier.substringAfter(':', identifier)
        return when (kind) {
            "email" -> Emails.normalize(value)
            "username" -> core.loginIdOf(value)?.let { "loginId:$it" } ?: Emails.normalize(value)
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
        if (announced.add(dev.sumin.skeleton.common.project.ProjectContext.scopedKey("$scope:${AccountRateLimits.digest(key)}@$windowIndex"))) {
            if (announced.size > MAX_REMEMBERED) announced.clear()
            core.events.publish(AccountEventType.LOGIN_THROTTLED, null, attempt.clientIp, mapOf("scope" to label, "id" to eventIdOf(core, attempt.identifier)))
        }
        throw RateLimitedException(a.retryAfterSeconds, AuthErrorCode.TOO_MANY_ATTEMPTS)
    }

    private companion object { const val MAX_REMEMBERED = 10_000 }
}

/**
 * 이벤트에 싣는 입력의 식별자. 아이디로 읽힌 `username:` 입력(켜짐 + `@` 없음)만 열쇠 있는 해시([dev.sumin.skeleton.account.AccountBlocks.loginIdEventId])고,
 * 이메일 · `accountId:` 입력과 꺼짐은 지금과 같은 [AccountRateLimits.digest] 다.
 */
internal fun eventIdOf(core: AccountCore, identifier: String): String {
    if (identifier.substringBefore(':', "") == "username") {
        core.loginIdOf(identifier.substringAfter(':', identifier))?.let { return core.blocks.loginIdEventId(it) }
    }
    return AccountRateLimits.digest(identifier)
}

/** 로그인 결과를 계정 이벤트로 · 성공이면 계정 · 수단에 마지막 로그인 시각을 찍는다 */
class LoginRecorder(private val core: AccountCore) : LoginHooks {
    override fun onFailure(attempt: LoginAttempt, reason: LoginFailure, account: AuthAccount?) {
        core.events.publish(
            AccountEventType.LOGIN_FAILURE, account?.accountId, attempt.clientIp,
            mapOf("method" to SignInMethods.PASSWORD, "reason" to reason.name, "id" to eventIdOf(core, attempt.identifier)),
        )
    }

    override fun onSuccess(attempt: LoginAttempt, account: AuthAccount) {
        val now = core.time.now()
        core.accounts.update(account.accountId, dev.sumin.skeleton.account.AccountPatch(lastLoginAt = now), now)
        account.email.takeIf { it.isNotEmpty() }?.let { email -> core.accounts.findIdentity(SignInMethods.PASSWORD, email)?.let { core.accounts.touchIdentity(it.id, now) } }
        core.events.publish(AccountEventType.LOGIN_SUCCESS, account.accountId, attempt.clientIp, mapOf("method" to SignInMethods.PASSWORD))
    }
}
