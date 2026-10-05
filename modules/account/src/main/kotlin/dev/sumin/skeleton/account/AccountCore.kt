package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountRateLimits
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.abuse.CaptchaGate
import dev.sumin.skeleton.account.events.AccountEventPublisher
import dev.sumin.skeleton.account.mail.AccountLinks
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.password.PasswordHasher
import dev.sumin.skeleton.account.password.PasswordPolicy
import dev.sumin.skeleton.account.token.OneTimeTokens
import dev.sumin.skeleton.auth.session.SessionRevoker
import dev.sumin.skeleton.common.time.TimeProvider
import java.security.SecureRandom
import java.util.HexFormat
import java.util.Locale

/** 서비스들이 함께 쓰는 협력자 묶음 — 자동설정이 한 번 만들어 모든 서비스에 준다 */
class AccountCore(
    val accounts: AccountRepository,
    val props: AccountProperties,
    val time: TimeProvider,
    val events: AccountEventPublisher,
    val hasher: PasswordHasher,
    val policy: PasswordPolicy,
    val tokens: OneTimeTokens,
    val mailer: AccountMailer,
    val links: AccountLinks,
    val tasks: AccountTaskRunner,
    val limits: AccountRateLimits,
    val captcha: CaptchaGate,
    /** 세션을 닫는 곳 — `auth-session` 이 없으면 null (그러면 닫을 세션이 없다) */
    val sessions: () -> SessionRevoker?,
    val bootstrap: AdminBootstrap,
) {
    fun newAccountId(): String = "acc_" + randomHex()

    fun newIdentityId(): String = "idn_" + randomHex()

    private fun randomHex() = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))

    private companion object { val random = SecureRandom() }
}

object Emails {
    /** 비교 · 저장용: 앞뒤 공백 제거 + 소문자 (로케일 무관) */
    fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    /** 아주 느슨한 모양 검사 — 진짜 검증은 메일이 닿는 것이다 */
    fun plausible(email: String): Boolean = email.length <= 254 && Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)
}

/** 기본 역할 · 로그인 수단 코드 상수 */
object SignInMethods {
    const val PASSWORD = "password"
    const val MAGIC_LINK = "magic_link"
}
