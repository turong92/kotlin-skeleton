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
    /**
     * 이메일로 계정 하나 — 저장소가 어떤 정렬 규칙으로 찾았든 **저장된 주소가 정규화된 입력과 글자 그대로 같을 때만** 돌려준다
     * (대소문자 · 악센트를 같게 보는 DB 정렬이 `victim@gmäil.com` 으로 `victim@gmail.com` 계정을 내주지 못하게 — 이것이 마지막 방어선이다).
     * [email] 은 이미 [Emails.normalize] 를 거친 값이어야 한다.
     */
    fun accountByEmail(email: String): Account? = accounts.findByEmail(email)?.takeIf { it.email == email }

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
