package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.signin.IdentityService
import dev.sumin.skeleton.account.signin.IdentityView
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.account.abuse.RateLimitedException
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
    /** `skeleton.account.email.required=true` 일 때만 값이 있다(꺼져 있으면 키가 없다): 이 계정에 주소가 아직 없어 허용 목록 밖 API 가 403 `ACCOUNT.EMAIL_REQUIRED` 인가 */
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val emailRequired: Boolean? = null,
    /**
     * "쓰기 때" 방식(`email.required-when=WRITE`)에서만 값이 있다(그 밖에는 키가 없다): 주소가 없어 허용 목록 밖의 **변경 요청**이 403 `ACCOUNT.EMAIL_REQUIRED` 인가.
     * 이 방식에서 [emailRequired] 는 키가 있고 늘 false 다(로그인 직후에 막지 않는다) — 그 값을 읽는 쪽의 뜻이 바뀌지 않게 키를 나눴다.
     */
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val emailRequiredToWrite: Boolean? = null,
    /**
     * 연락받을 이메일(확인된 주소). `skeleton.account.contact-email.enabled` 가 **꺼져 있으면 키가 없다**(null) — 켜져 있는데 아직 없으면 키가 있고 값이 `null`(빈 [java.util.Optional]).
     * 프론트가 "꺼짐" 과 "아직 없음" 을 가를 수 있게.
     */
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val contactEmail: java.util.Optional<String>? = null,
    /**
     * 로그인 아이디(`skeleton.account.login-id.enabled=true` 일 때만). **꺼져 있으면 키가 없다**(null) — 켜져 있는데 아직 안 정했으면 키가 있고 값이 `null`(빈 [java.util.Optional]): 그것이 곧 "정해야 함"(게이트)이다.
     */
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val loginId: java.util.Optional<String>? = null,
    /**
     * 프로필 사진의 업로드 키 - **사진 기능이 꺼져 있으면(`storage` 없음) 키가 없다**(null). 켜져 있는데 아직 없으면 키가 있고 값이 `null`(빈 [java.util.Optional]).
     * 키는 본인에게만 나간다(이 응답). 남에게 보이는 모습은 [avatar].
     */
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val avatarKey: java.util.Optional<String>? = null,
    /** 사진이 있고 기능이 켜졌을 때만(그 밖에는 키가 없다) - 공개 주소 · 변환본 · 변환 중 표시. 공개 영역이 꺼졌으면 세 칸이 비어 있다(서명 주소 엔드포인트로 본다) */
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val avatar: AvatarView? = null,
)

/** [avatarKey]: null = 그대로, 빈 [java.util.Optional] = 지움, 값 = 설정 */
data class ProfileChange(
    val displayName: String? = null,
    val locale: String? = null,
    val timeZone: String? = null,
    val avatarKey: java.util.Optional<String>? = null,
)

/** `me` — 내 프로필 읽기 · 고치기 (이름 · 로케일 · 시간대 · 사진) */
class ProfileService(private val core: AccountCore, registry: SignInMethodRegistry, private val avatars: AccountAvatars = AccountAvatars.NONE) {
    private val identities = IdentityService(core, registry)

    fun me(accountId: String): MeView {
        val a = core.accounts.findById(accountId)?.takeIf { it.status != AccountStatus.ERASED } ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val methods = identities.list(accountId)
        val pending = core.challenges.findOpen(ChallengePurposes.EMAIL_CHANGE, accountId)
        val writeMode = core.props.email.required && core.props.email.requiredWhen == AccountProperties.Email.RequiredWhen.WRITE
        return MeView(
            a.id, a.email, a.emailVerified, a.displayName, a.visibleTag, a.locale, a.timeZone, a.roles, a.status, a.createdAt,
            hasPassword = methods.any { it.method == SignInMethods.PASSWORD }, methods = methods,
            pendingEmail = pending?.payload?.let(EmailChangePayload::target), pendingEmailExpiresAt = pending?.expiresAt,
            emailRequired = if (core.props.email.required) a.email == null && !writeMode else null,
            emailRequiredToWrite = if (writeMode) a.email == null else null,
            contactEmail = if (core.props.contactEmail.enabled) java.util.Optional.ofNullable(a.contactEmail) else null,
            loginId = if (core.props.loginId.enabled) java.util.Optional.ofNullable(a.loginId) else null,
            avatarKey = if (avatars.enabled) java.util.Optional.ofNullable(a.avatarKey) else null,
            avatar = avatars.view(a.avatarKey) { core.accounts.findById(accountId)?.avatarKey == a.avatarKey },
        )
    }

    /** 내 사진을 볼 짧은 서명 주소 - 사진이 없거나 서명 읽기가 꺼졌으면 null */
    fun avatarSigned(accountId: String): SignedAvatar? =
        core.accounts.findById(accountId)?.takeIf { it.status != AccountStatus.ERASED }?.avatarKey?.let(avatars::signed)

    fun update(accountId: String, change: ProfileChange): MeView {
        // 지운 계정의 옛 액세스 토큰으로는 아무것도 쓰지 못한다 — 저장소도 같은 조건으로 막는다(검사와 쓰기 사이 틈)
        val current = core.accounts.findById(accountId)?.takeIf { it.status != AccountStatus.ERASED } ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        // 값 검사는 모두 쓰기 전에 — 하나라도 어긋나면 아무것도 바꾸지 않는다. 운영자 역할 계정은 예약어도 쓸 수 있다
        val name = change.displayName?.let { core.names.accept(it, required = true, exemptReserved = core.superRole in current.roles) }
        change.locale?.let { if (ProfileRules.locale(it) == null) invalid("locale") }
        change.timeZone?.let { if (ProfileRules.timeZone(it) == null) invalid("timeZone") }
        // 사진 키: 기능이 꺼졌거나 내 업로드가 아니면 400 - 이름 · 한도보다 먼저 거른다 (같은 키 · 지움은 쓰기 없이 지나간다)
        val avatarChange = change.avatarKey
        if (avatarChange != null && !avatars.enabled) invalid("avatarKey")
        val newAvatar = avatarChange?.takeIf { it.isPresent }?.let { avatars.accept(accountId, it.get(), current = current.avatarKey) }
        val avatarPatch = when {
            avatarChange == null || newAvatar == current.avatarKey -> null
            else -> newAvatar
        }
        val clearAvatar = avatarChange != null && !avatarChange.isPresent && current.avatarKey != null
        // 이름이 겹치면 409 — 다른 필드를 바꾸기 전에. 한도는 **값 검사를 통과한 실제 변경**만 센다 (같은 값 · 거절된 요청은 안 센다)
        if (name != null && name != current.displayName) {
            val limit = core.props.displayName
            if (limit.changeLimit > 0) {
                val allowance = core.limits.acquire("rename:account", accountId, limit.changeLimit, limit.changeWindow)
                if (!allowance.allowed) throw RateLimitedException(allowance.retryAfterSeconds)
            }
        }
        if (name != null) rename(current, name)
        core.accounts.update(
            accountId,
            AccountPatch(locale = ProfileRules.locale(change.locale), timeZone = ProfileRules.timeZone(change.timeZone), avatarKey = avatarPatch, clearAvatar = clearAvatar),
            core.time.now(),
        ) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        // 새 사진은 쓰기 직후 공개 영역에 복사하고, 바뀌거나 지워져 더 쓰이지 않는 옛 키의 공개 복사는 지운다 (다른 곳이 쓰는 키는 retire 가 남긴다)
        if (avatarPatch != null) avatars.publish(avatarPatch) { core.accounts.findById(accountId)?.avatarKey == avatarPatch }
        if ((avatarPatch != null || clearAvatar) && current.avatarKey != null) avatars.retire(current.avatarKey)
        return me(accountId)
    }

    /**
     * 닉네임 변경. 비교용 키가 그대로면(대소문자 · 전각만 바뀜) 꼬리표를 **지킨다**, 키가 바뀌면 새로 뽑는다. 방식과 맞지 않는 옛 꼬리표(방식을 바꾸기 전에 저장된 것)도 새로 뽑는다.
     * (키, 꼬리표) 유니크가 겹치면 UNIQUE 는 409, TAGGED 는 [AccountCore.placeName] 이 다시 시도하고 키 안이 가득 차야 409.
     */
    private fun rename(current: Account, name: String) {
        val key = core.names.key(name)
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
