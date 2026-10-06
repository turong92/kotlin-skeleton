package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.signin.IdentityService
import dev.sumin.skeleton.account.signin.IdentityView
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import java.time.Instant

data class MeView(
    val id: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val locale: String?,
    val timeZone: String?,
    val roles: Set<String>,
    val status: AccountStatus,
    val createdAt: Instant,
    val hasPassword: Boolean,
    val methods: List<IdentityView>,
    /** 이메일 변경을 요청했고 새 주소로 간 코드의 입력을 기다리는 중이면 그 주소와 코드 만료 시각 (아니면 null) */
    val pendingEmail: String? = null,
    val pendingEmailExpiresAt: Instant? = null,
)

data class ProfileChange(val displayName: String? = null, val locale: String? = null, val timeZone: String? = null)

/** `me` — 내 프로필 읽기 · 고치기 (이름 · 로케일 · 시간대) */
class ProfileService(private val core: AccountCore, registry: SignInMethodRegistry) {
    private val identities = IdentityService(core, registry)

    fun me(accountId: String): MeView {
        val a = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val methods = identities.list(accountId)
        val pending = core.challenges.findOpen(ChallengePurposes.EMAIL_CHANGE, accountId)
        return MeView(
            a.id, a.email, a.emailVerified, a.displayName, a.locale, a.timeZone, a.roles, a.status, a.createdAt,
            hasPassword = methods.any { it.method == SignInMethods.PASSWORD }, methods = methods,
            pendingEmail = pending?.payload, pendingEmailExpiresAt = pending?.expiresAt,
        )
    }

    fun update(accountId: String, change: ProfileChange): MeView {
        change.displayName?.let { if (it.trim().isEmpty() || it.trim().length > ProfileRules.MAX_DISPLAY_NAME) invalid("displayName") }
        change.locale?.let { if (ProfileRules.locale(it) == null) invalid("locale") }
        change.timeZone?.let { if (ProfileRules.timeZone(it) == null) invalid("timeZone") }
        core.accounts.update(
            accountId,
            AccountPatch(displayName = ProfileRules.displayName(change.displayName), locale = ProfileRules.locale(change.locale), timeZone = ProfileRules.timeZone(change.timeZone)),
            core.time.now(),
        ) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        return me(accountId)
    }

    private fun invalid(field: String): Nothing = throw ApplicationException("Invalid $field", PlatformErrorCode.VALIDATION_FAILED)
}
