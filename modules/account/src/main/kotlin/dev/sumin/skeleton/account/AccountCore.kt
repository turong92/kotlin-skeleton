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
    /** 가입에 약관 동의를 묶는 고리 — `legal` 이 없으면 null (그러면 동의를 다루지 않는다) */
    val consents: () -> dev.sumin.skeleton.common.consent.SignUpConsentGate? = { null },
    /** 계정 만들기와 동의 기록을 한 트랜잭션으로 묶는 곳 — `account-jdbc` 가 DB 트랜잭션으로 낸다 */
    val atomic: AccountTransaction = AccountTransaction.NONE,
    /** 운영자가 지운 정지 계정의 재가입 차단 */
    val blocks: AccountBlocks = AccountBlocks.local(time),
    /** 주기 정리를 인스턴스 하나만 하게 하는 임대 */
    val lease: AccountMaintenanceLease = InMemoryAccountMaintenanceLease(time),
    /** 1회용 매직 링크를 만드는 고리 — `auth-magic-link` 가 없으면 null ("이미 계정이 있어요" 메일에서 그 줄이 빠진다) */
    val magicLinks: () -> MagicLinkIssuer? = { null },
    /** 닉네임 규칙 · 꼬리표 · 자동 닉네임 */
    val names: DisplayNames = DisplayNames(props.displayName),
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

    /**
     * **한 이메일 주소**에 걸리는 코드 예산 — 가입 시도 · 이메일 변경(대상 주소)이 **같은 버킷**을 쓴다. 계정을 몇 개 만들든, 어느 길로 오든 한 메일함에 대한 총량이 하나다.
     * ([mayOpenCodeFor]: 추측할 수 있는 챌린지를 새로 열어도 되나 · [mayMailCodeTo]: 그 주소로 코드 · "이미 계정이 있어요" 메일을 또 보내도 되나 · [spendGuess]: 추측 한 번)
     */
    fun mayOpenCodeFor(email: String): Boolean {
        val v = props.verification
        return limits.acquire("signup:email", email, v.signUpAttemptsPerEmail, v.perEmailWindow).allowed
    }

    fun mayMailCodeTo(email: String): Boolean {
        val v = props.verification
        return limits.acquire("verification:email", email, v.perEmail, v.perEmailWindow).allowed
    }

    /** 이 주소의 코드를 맞춰 보는 한 번 — 창 안의 총 추측 수가 [AccountProperties.Verification.guessesPerEmail] 를 넘으면 429 (시도를 깎기 **전에** 부른다) */
    fun spendGuess(email: String) {
        val v = props.verification
        val a = limits.acquire("guess:email", email, v.guessesPerEmail, v.perEmailWindow)
        if (!a.allowed) throw dev.sumin.skeleton.account.abuse.RateLimitedException(a.retryAfterSeconds)
    }

    /**
     * 비밀번호 재설정 링크를 만든다 — 재설정 요청([PasswordService.forgot])과 "이미 계정이 있어요" 메일이 **같은 길**이다: 같은 계정 상태 규칙(ACTIVE · PENDING_VERIFICATION 만),
     * 같은 주소별 한도(`reset.per-email`), 같은 토큰(새 토큰이 이전 것을 닫는다). 못 만들면(상태 · 한도) null
     */
    fun issueResetLink(account: Account): IssuedLink? {
        if (account.status != AccountStatus.ACTIVE && account.status != AccountStatus.PENDING_VERIFICATION) return null
        val email = account.email ?: return null
        val r = props.reset
        if (!limits.acquire("reset:email", email, r.perEmail, r.perEmailWindow).allowed) return null
        val raw = tokens.issue(TokenPurposes.PASSWORD_RESET, email, account.id, r.ttl)
        return IssuedLink(links.reset(raw), r.ttl.toMinutes())
    }

    /** 코드를 [ttl] 동안 쓸 수 있게 방금 보낸 응답의 두 시각 ([CodeWindow]) — 메일을 실제로 보냈든 안 보냈든 같은 계산 */
    fun codeWindow(ttl: java.time.Duration): CodeWindow {
        val now = time.now()
        return CodeWindow(now.plus(ttl), now.plus(props.verification.resendCooldown))
    }

    /** 새 로그인 수단이 붙었다고 계정 주소에 알린다 (내가 한 일이 아니면 알아채도록) */
    fun notifyIdentityLinked(accountId: String, method: String) {
        val account = accounts.findById(accountId) ?: return
        val email = account.email ?: return
        mailer.send(AccountMail(MailKind.IDENTITY_LINKED_NOTICE, email, account.locale, vars = mapOf("method" to method)))
    }

    /**
     * 탈퇴 취소 · 운영자 복구 · 정지 해제로 **되살아날 때의 상태**: 확인된(또는 주소가 없는) 계정은 ACTIVE, 확인 전 계정은 PENDING_VERIFICATION —
     * 단 `sign-up.email-verification=false` 인 앱에는 확인 단계가 없다(가입한 계정이 곧 ACTIVE)이므로 늘 ACTIVE 다 (그렇지 않으면 ACTIVE 였던 계정이 확인할 길 없는 상태로 되살아난다)
     */
    fun reopenedStatus(a: Account): AccountStatus =
        if (!props.signUp.emailVerification || a.emailVerified || a.email == null) AccountStatus.ACTIVE else AccountStatus.PENDING_VERIFICATION

    /** [placeName] 한 번의 시도 결과 — [STORED] 저장됨 · [CLASH] (키, 꼬리표) 유니크에 걸림 · [REFUSED] 닉네임과 상관없는 이유(이메일 · 수단 · 계정 없음) */
    enum class Slot { STORED, CLASH, REFUSED }

    /**
     * 닉네임 [key] 에 꼬리표를 붙여 [attempt] 로 저장해 본다. NONE 은 꼬리표 없이 한 번, UNIQUE 는 고정 꼬리표로 한 번(겹치면 [Slot.CLASH]),
     * TAGGED 는 무작위 꼬리표를 몇 번 시도하고 그래도 겹치면 키 안의 빈 꼬리표를 직접 찾는다 — 빈 것이 없으면(9999 명) [Slot.CLASH].
     * 경합은 유니크 제약이 가르고 여기서는 다시 시도한다 (먼저 읽고 쓰지 않는다).
     */
    fun placeName(key: String, attempt: (String?) -> Slot): Slot {
        val tagged = names.uniqueness == AccountProperties.DisplayName.Uniqueness.TAGGED
        repeat(if (tagged) TAG_TRIES else 1) {
            val r = attempt(names.tagCandidate())
            if (r != Slot.CLASH) return r
        }
        if (!tagged) return Slot.CLASH
        val tag = names.freeTag(accounts.displayTagsOf(key)) ?: return Slot.CLASH
        return attempt(tag)
    }

    enum class NamedInsert { INSERTED, REFUSED, NAME_TAKEN }

    data class NamedInsertResult(val outcome: NamedInsert, val account: Account)

    /**
     * [base] (닉네임 · 키 · 꼬리표는 비어 있다) 를 닉네임을 붙여 저장한다 — [write] 가 실제 저장(트랜잭션 · 동의 기록 포함)이고 **키가 겹치면 false** 를 돌려준다.
     * [requested] 가 없으면 `fallback=GENERATED` 일 때 자동 닉네임 · 아니면 이름 없이. UNIQUE 에서 자동 닉네임이 겹치면 새로 뽑는다.
     * [requested] 가 겹치면: [strict](사람이 낸 닉네임)면 [NamedInsert.NAME_TAKEN], 아니면(제공자 · 시드가 준 이름) 이름을 버리고 자동 닉네임 · 이름 없이 계속한다.
     * [NamedInsert.REFUSED] 는 이메일 · 로그인 수단이 이미 있다는 뜻 — 닉네임과 무관하다.
     */
    fun insertNamed(base: Account, identities: List<Identity>, requested: String?, strict: Boolean, write: (Account) -> Boolean): NamedInsertResult {
        var name = requested
        var generatedTries = 0
        val generate = names.config.fallback == AccountProperties.DisplayName.Fallback.GENERATED
        while (true) {
            val chosen = name ?: if (generate && generatedTries < GENERATED_TRIES) names.generated() else null
            if (chosen == null) {
                val plain = base.copy(displayName = null, displayNameKey = null, displayTag = null)
                return NamedInsertResult(if (write(plain)) NamedInsert.INSERTED else NamedInsert.REFUSED, plain)
            }
            val key = DisplayNameRules.key(chosen)
            var stored: Account? = null
            val slot = placeName(key) { tag ->
                val candidate = base.copy(displayName = chosen, displayNameKey = key, displayTag = tag)
                when {
                    write(candidate) -> { stored = candidate; Slot.STORED }
                    names.uniqueness == AccountProperties.DisplayName.Uniqueness.NONE || refusedForOtherReason(base, identities) -> Slot.REFUSED
                    else -> Slot.CLASH
                }
            }
            when (slot) {
                Slot.STORED -> return NamedInsertResult(NamedInsert.INSERTED, stored!!)
                Slot.REFUSED -> return NamedInsertResult(NamedInsert.REFUSED, base)
                Slot.CLASH -> if (name != null) { if (strict) return NamedInsertResult(NamedInsert.NAME_TAKEN, base); name = null } else generatedTries++
            }
        }
    }

    private fun refusedForOtherReason(base: Account, identities: List<Identity>): Boolean =
        accounts.findById(base.id) != null || base.email?.let { accounts.findByEmail(it) } != null || identities.any { accounts.findIdentity(it.method, it.subject) != null }

    fun newAccountId(): String = "acc_" + randomHex()

    fun newIdentityId(): String = "idn_" + randomHex()

    private fun randomHex() = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))

    private companion object {
        val random = SecureRandom()

        /** 무작위 꼬리표를 몇 번 시도하고 나서 빈 꼬리표를 직접 찾나 */
        const val TAG_TRIES = 8

        /** 자동 닉네임이 UNIQUE 에서 겹칠 때 새로 뽑는 횟수 — 넘으면 이름 없이 (여섯 자리 16진수는 사실상 겹치지 않는다) */
        const val GENERATED_TRIES = 10
    }
}

/**
 * 계정을 만드는 쓰기와 그와 함께 가야 하는 쓰기(약관 동의 기록)를 **한 트랜잭션**으로 묶는 고리. 저장소가 트랜잭션을 아는 모듈(`account-jdbc`)이 구현하고,
 * 기본은 그냥 실행한다 (메모리 저장소는 되돌릴 것이 없다). [block] 이 던지면 안에서 한 쓰기는 모두 되돌려진다.
 */
interface AccountTransaction {
    fun <T> run(block: () -> T): T

    companion object {
        val NONE: AccountTransaction = object : AccountTransaction {
            override fun <T> run(block: () -> T): T = block()
        }
    }
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
