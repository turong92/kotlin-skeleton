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
    /** 같은 닉네임을 구분하는 4자리 꼬리표 — `uniqueness=TAGGED` 일 때만 값이 있다 */
    val displayTag: String?,
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
        val a = core.accounts.findById(accountId)?.takeIf { it.status != AccountStatus.ERASED } ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val methods = identities.list(accountId)
        val pending = core.challenges.findOpen(ChallengePurposes.EMAIL_CHANGE, accountId)
        return MeView(
            a.id, a.email, a.emailVerified, a.displayName, a.visibleTag, a.locale, a.timeZone, a.roles, a.status, a.createdAt,
            hasPassword = methods.any { it.method == SignInMethods.PASSWORD }, methods = methods,
            pendingEmail = pending?.payload?.let(EmailChangePayload::target), pendingEmailExpiresAt = pending?.expiresAt,
        )
    }

    fun update(accountId: String, change: ProfileChange): MeView {
        // 지운 계정의 옛 액세스 토큰으로는 아무것도 쓰지 못한다 — 저장소도 같은 조건으로 막는다(검사와 쓰기 사이 틈)
        val current = core.accounts.findById(accountId)?.takeIf { it.status != AccountStatus.ERASED } ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        // 값 검사는 모두 쓰기 전에 — 하나라도 어긋나면 아무것도 바꾸지 않는다. 운영자 역할 계정은 예약어도 쓸 수 있다
        val name = change.displayName?.let { core.names.accept(it, required = true, exemptReserved = core.props.admin.role in current.roles) }
        change.locale?.let { if (ProfileRules.locale(it) == null) invalid("locale") }
        change.timeZone?.let { if (ProfileRules.timeZone(it) == null) invalid("timeZone") }
        // 이름이 겹치면 409 — 다른 필드를 바꾸기 전에
        if (name != null) rename(current, name)
        core.accounts.update(
            accountId,
            AccountPatch(locale = ProfileRules.locale(change.locale), timeZone = ProfileRules.timeZone(change.timeZone)),
            core.time.now(),
        ) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        return me(accountId)
    }

    /**
     * 닉네임 변경. 비교용 키가 그대로면(대소문자 · 전각만 바뀜) 꼬리표를 **지킨다**, 키가 바뀌면 새로 뽑는다. 방식과 맞지 않는 옛 꼬리표(방식을 바꾸기 전에 저장된 것)도 새로 뽑는다.
     * (키, 꼬리표) 유니크가 겹치면 UNIQUE 는 409, TAGGED 는 [AccountCore.placeName] 이 다시 시도하고 키 안이 가득 차야 409.
     */
    private fun rename(current: Account, name: String) {
        val key = DisplayNameRules.key(name)
        val now = core.time.now()
        val keep = key == current.displayNameKey && tagFitsMode(current.displayTag)
        val slot = if (keep) {
            storeName(current.id, name, key, current.displayTag, now)
        } else {
            core.placeName(key) { tag -> storeName(current.id, name, key, tag, now) }
        }
        when (slot) {
            AccountCore.Slot.STORED -> Unit
            AccountCore.Slot.CLASH -> throw AccountException(AccountErrorCode.DISPLAY_NAME_TAKEN)
            AccountCore.Slot.REFUSED -> throw AccountException(AccountErrorCode.NOT_FOUND)
        }
    }

    private fun storeName(id: String, name: String, key: String, tag: String?, now: java.time.Instant): AccountCore.Slot =
        when (core.accounts.setDisplayName(id, name, key, tag, now)) {
            SetNameResult.DONE -> AccountCore.Slot.STORED
            SetNameResult.TAKEN -> AccountCore.Slot.CLASH
            SetNameResult.NOT_FOUND -> AccountCore.Slot.REFUSED
        }

    private fun tagFitsMode(tag: String?) = when (core.names.uniqueness) {
        AccountProperties.DisplayName.Uniqueness.NONE -> tag == null
        AccountProperties.DisplayName.Uniqueness.UNIQUE -> tag == DisplayNames.NO_TAG
        AccountProperties.DisplayName.Uniqueness.TAGGED -> tag != null && tag != DisplayNames.NO_TAG
    }

    private fun invalid(field: String): Nothing = throw ApplicationException("Invalid $field", PlatformErrorCode.VALIDATION_FAILED)
}
