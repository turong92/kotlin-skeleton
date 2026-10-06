package dev.sumin.skeleton.account.signin

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountAuthRepository
import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Emails
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.ProfileRules
import dev.sumin.skeleton.account.SignInMethods
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.auth.account.AuthAccount

/**
 * 로그인 수단이 증명을 마친 뒤 넘기는 한 건. [email] · [emailVerified] 는 수단이 아는 만큼 (소셜 제공자의 이메일, 매직 링크의 주소).
 * [allowSignUp] 은 호출한 수단이 자기 설정(`social.sign-up` · 매직 링크의 `sign-up`)으로 정한다.
 */
data class SignInProof(
    val method: String,
    val subject: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val displayName: String? = null,
    val locale: String? = null,
    val ip: String? = null,
    val allowSignUp: Boolean = false,
)

/**
 * 모든 비밀번호 없는 수단(소셜 · 매직 링크 · 앞으로 더할 것)이 지나는 한 곳: 증명 → 계정 찾기/만들기 → 충돌 규칙 → 로그인 기록.
 * 토큰 발급은 하지 않는다 — 돌려받은 [AuthAccount] 를 호출자가 `AuthTokenResponseFactory.issue` 에 넘긴다 (막힌 계정 거르기 · 세션 열기는 거기 있다).
 *
 * 충돌 규칙 (docs/accounts.md 위협 모델 "소셜 병합 탈취"):
 *  - 메일함 증명 수단(매직 링크)은 같은 이메일의 기존 계정에 붙는다 — 메일함의 주인이 어차피 비밀번호 재설정으로 들어올 수 있다.
 *  - 소셜은 **제공자가 확인한 이메일**이 기존 계정과 같으면 기본은 병합하지 않고 ACCOUNT.SOCIAL_EMAIL_CONFLICT. `social.merge-on-verified-email` 을 켜도 두 쪽 이메일이 모두 확인된 경우만.
 *  - 제공자가 확인하지 않은 이메일은 **없는 것으로 친다**: 저장하지 않고(남의 주소 선점 방지) 충돌도 알리지 않는다(주소 존재 여부 조회 방지).
 */
class AccountSignInService(private val core: AccountCore, val registry: SignInMethodRegistry) {
    val identities = IdentityService(core, registry)
    private val auth = AccountAuthRepository(core)

    /** 로그인할 계정을 돌려준다. 모르는 주체이고 가입을 허용하지 않았거나 삭제된 계정이면 null */
    fun signIn(proof: SignInProof): AuthAccount? {
        val method = registry.require(proof.method)
        val subject = method.normalize(proof.subject)
        val email = proof.email?.let(Emails::normalize)?.takeIf { proof.emailVerified && Emails.plausible(it) }

        val existing = core.accounts.findIdentity(method.code, subject)
        val account = if (existing != null) core.accounts.findById(existing.accountId) else resolveNew(method, subject, email, proof)
        if (account == null || account.status == AccountStatus.DELETED) return null

        val now = core.time.now()
        existing?.let { core.accounts.touchIdentity(it.id, now) } ?: core.accounts.findIdentity(method.code, subject)?.let { core.accounts.touchIdentity(it.id, now) }
        // 메일함을 증명한 로그인(매직 링크, 제공자가 확인한 같은 이메일)은 이메일 확인으로 친다
        val proven = account.email != null && account.email == email && (method.provesEmail || proof.emailVerified)
        if (proven && !account.emailVerified) {
            discardUnprovenPassword(account)
            core.accounts.markEmailVerified(account.id, now)
            core.events.publish(AccountEventType.EMAIL_VERIFIED, account.id, proof.ip, mapOf("method" to method.code))
        }
        core.accounts.update(account.id, AccountPatch(lastLoginAt = now), now)
        core.events.publish(AccountEventType.LOGIN_SUCCESS, account.id, proof.ip, mapOf("method" to method.code))
        val fresh = core.accounts.findById(account.id) ?: return null
        core.bootstrap.afterVerified(fresh)
        return auth.toAuth(core.accounts.findById(account.id) ?: fresh)
    }

    /**
     * 주소의 메일함이 방금 증명됐는데 그 계정의 이메일은 아직 미확인이었다. 그 계정의 비밀번호 수단은 **메일함 주인이 아닌 누군가**(가입 요청을 보낸 쪽)가
     * 정한 것이라, 확인으로 함께 살려 두면 사전 탈취가 된다 — 미확인 비밀번호는 버리고, 그때 열려 있던 세션 · 인증 링크도 닫는다.
     */
    private fun discardUnprovenPassword(account: Account) {
        val email = account.email ?: return
        core.accounts.identitiesOf(account.id).filter { it.method == SignInMethods.PASSWORD && !it.verified }.forEach { core.accounts.removeIdentity(account.id, it.id) }
        core.tokens.invalidate(TokenPurposes.VERIFY_EMAIL, email)
        core.sessions()?.revokeAll(account.id, null)
    }

    private fun resolveNew(method: SignInMethod, subject: String, email: String?, proof: SignInProof): Account? {
        val owner = email?.let(core::accountByEmail)
        if (owner != null) {
            if (owner.status == AccountStatus.DELETED) return null
            // 이미 있는 계정에 붙는 것은 가입이 아니다 — 메일함 증명 수단은 `sign-up=false` 여도 기존 계정으로 들어온다.
            // 소셜 병합은 가입 허용을 따르고(충돌 알림도 가입 시도의 일부), 둘 다 아니면 새로 만들지도 붙이지도 않는다
            // 소셜 병합: 제공자가 확인한 이메일(+ 정확 일치)이면. 기존 계정의 이메일이 미확인이면 제공자의 확인이 메일함 증명이라 위 [discardUnprovenPassword] 가 돈다
            val attach = method.provesEmail || (proof.allowSignUp && core.props.social.mergeOnVerifiedEmail && proof.emailVerified)
            if (attach) return attachIdentity(owner, method, subject, proof) ?: raced(method, subject)
            if (!proof.allowSignUp) return null
            throw AccountException(AccountErrorCode.SOCIAL_EMAIL_CONFLICT)
        }
        if (!proof.allowSignUp) return null
        return create(method, subject, email, proof) ?: raced(method, subject)
    }

    private fun attachIdentity(owner: Account, method: SignInMethod, subject: String, proof: SignInProof): Account? {
        val identity = Identity(core.newIdentityId(), owner.id, method.code, subject, verified = true, createdAt = core.time.now())
        if (!core.accounts.addIdentity(identity)) return null
        core.events.publish(AccountEventType.IDENTITY_LINKED, owner.id, proof.ip, mapOf("method" to method.code, "auto" to "true"))
        core.notifyIdentityLinked(owner.id, method.code)
        return owner
    }

    private fun create(method: SignInMethod, subject: String, email: String?, proof: SignInProof): Account? {
        val now = core.time.now()
        val account = Account(
            id = core.newAccountId(), email = email, emailVerified = email != null, status = AccountStatus.ACTIVE, roles = core.props.defaultRoles,
            displayName = ProfileRules.displayName(proof.displayName), locale = ProfileRules.locale(proof.locale), timeZone = null, createdAt = now, updatedAt = now,
        )
        val identity = Identity(core.newIdentityId(), account.id, method.code, subject, verified = true, createdAt = now)
        if (!core.accounts.insert(account, listOf(identity))) return null
        core.events.publish(AccountEventType.SIGN_UP, account.id, proof.ip, mapOf("method" to method.code))
        return account
    }

    /** 유니크 키가 우리를 졌다 — 이긴 쪽이 만든 계정(같은 수단 · 주체)이 있으면 그것, 아니면 이메일이 그 사이 쓰였다 */
    private fun raced(method: SignInMethod, subject: String): Account? =
        core.accounts.findByIdentity(method.code, subject) ?: throw AccountException(AccountErrorCode.SOCIAL_EMAIL_CONFLICT)
}
