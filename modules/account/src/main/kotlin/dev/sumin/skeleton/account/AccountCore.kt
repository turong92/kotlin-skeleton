package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountRateLimits
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.abuse.CaptchaGate
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.challenge.Challenges
import dev.sumin.skeleton.account.events.AccountEventPublisher
import dev.sumin.skeleton.account.mail.AccountLinks
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.password.PasswordHasher
import dev.sumin.skeleton.account.password.PasswordPolicy
import dev.sumin.skeleton.account.token.OneTimeTokens
import dev.sumin.skeleton.auth.session.SessionRevoker
import dev.sumin.skeleton.common.time.TimeProvider
import java.security.SecureRandom
import java.text.Normalizer
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
    val challenges: Challenges,
    /** 이메일 없는 계정의 소셜 다시 인증을 확인하는 고리 — `auth-social` 이 없으면 null */
    val socialReauth: () -> SocialReauthVerifier? = { null },
) {
    /**
     * 이메일로 계정 하나 — 저장소가 어떤 정렬 규칙으로 찾았든 **저장된 주소가 정규화된 입력과 글자 그대로 같을 때만** 돌려준다
     * (대소문자 · 악센트를 같게 보는 DB 정렬이 `victim@gmäil.com` 으로 `victim@gmail.com` 계정을 내주지 못하게 — 이것이 마지막 방어선이다).
     * [email] 은 이미 [Emails.normalize] 를 거친 값이어야 한다.
     */
    fun accountByEmail(email: String): Account? = accounts.findByEmail(email)?.takeIf { it.email == email }

    /**
     * 비밀번호가 바뀌었거나 메일함이 증명된 계정의 **그 전에 나간 민감한 코드 · 링크**를 모두 닫는다 — 이메일 변경 · 삭제 확인 · 다시 인증 코드, 같은 주소의 가입 시도, 매직 링크.
     * (탈취한 세션으로 요청해 둔 코드가, 주인이 비밀번호를 바꾼 뒤에도 살아 있으면 주소가 넘어간다). [alsoEmails]: 계정이 예전에 쓰던 주소(이메일 변경 뒤)도
     */
    fun closeSensitiveLinks(account: Account, vararg alsoEmails: String?) {
        challenges.deleteForAccount(account.id)
        (listOf(account.email) + alsoEmails).filterNotNull().distinct().forEach {
            tokens.invalidate(TokenPurposes.MAGIC_LINK, it)
            challenges.deleteBySubject(ChallengePurposes.SIGN_UP, it)
        }
    }

    /** 새 로그인 수단이 붙었다고 계정 주소에 알린다 (내가 한 일이 아니면 알아채도록) */
    fun notifyIdentityLinked(accountId: String, method: String) {
        val account = accounts.findById(accountId) ?: return
        val email = account.email ?: return
        mailer.send(AccountMail(MailKind.IDENTITY_LINKED_NOTICE, email, account.locale, vars = mapOf("method" to method)))
    }

    fun newAccountId(): String = "acc_" + randomHex()

    fun newIdentityId(): String = "idn_" + randomHex()

    private fun randomHex() = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))

    private companion object { val random = SecureRandom() }
}

/**
 * 이메일 규칙 — **여기 한 곳**이다 (저장 · 조회 · 한도 키 · 토큰 주인 · 메일 수신자 모두 [normalize] 를 거친 값):
 * 앞뒤 공백 제거 → 유니코드 NFC(결합 문자와 합성 문자를 같은 글자로) → 소문자(Locale.ROOT, 터키어 I 같은 로케일 의존 없음).
 * 점 · `+태그` 제거나 NFKC·IDN punycode 변환은 하지 **않는다** — 그것은 메일함 주인의 영역이고, 같게 보면 남의 주소를 같다고 말하게 된다.
 * 같은 주소인지는 DB 정렬에 맡기지 않고 [AccountCore.accountByEmail] 이 글자 그대로 비교한다 (MySQL 은 `utf8mb4_bin` 열도 함께).
 */
object Emails {
    fun normalize(raw: String): String = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /** 아주 느슨한 모양 검사 — 진짜 검증은 메일이 닿는 것이다 */
    fun plausible(email: String): Boolean = email.length <= 254 && Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)
}

/** 기본 역할 · 로그인 수단 코드 상수 */
object SignInMethods {
    const val PASSWORD = "password"
    const val MAGIC_LINK = "magic_link"
}
