package dev.sumin.skeleton.account.signin

import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AddIdentityResult
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.Reauth
import dev.sumin.skeleton.account.ReauthInput
import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.RemoveIdentityResult
import dev.sumin.skeleton.account.events.AccountEventType
import java.time.Instant

/** 로그인 수단 목록의 한 줄 — 이메일 계열만 [subject] 를 싣는다 (제공자 사용자 id 는 화면에 필요 없다) */
data class IdentityView(
    val id: String,
    val method: String,
    val subject: String?,
    val verified: Boolean,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val removable: Boolean,
)

/** 로그인한 계정이 자기 로그인 수단을 보고 · 붙이고 · 떼는 규칙 */
class IdentityService(private val core: AccountCore, private val registry: SignInMethodRegistry) {
    fun list(accountId: String): List<IdentityView> {
        val identities = core.accounts.identitiesOf(accountId)
        val credentials = identities.count { registry.find(it.method)?.countsAsCredential != false }
        return identities.map {
            val method = registry.find(it.method)
            IdentityView(
                id = it.id, method = it.method, subject = if (method?.exposesSubject == true) it.subject else null, verified = it.verified,
                createdAt = it.createdAt, lastUsedAt = it.lastUsedAt,
                removable = (method?.userRemovable ?: true) && (method?.countsAsCredential == false || credentials > 1),
            )
        }
    }

    /** 수단 하나를 계정에 붙인다. 증명(코드 교환 등)은 호출자가 이미 마쳤다. 수단 하나당 계정에 하나 */
    fun link(accountId: String, methodCode: String, subject: String, verified: Boolean, metadata: String? = null, expectEmailVerified: Boolean? = null): IdentityView {
        val method = registry.require(methodCode)
        val normalized = method.normalize(subject)
        if (core.accounts.identitiesOf(accountId).any { it.method == methodCode }) throw AccountException(AccountErrorCode.IDENTITY_EXISTS)
        // 운영자가 지운 계정의 제공자 주체 · 주소는 다른 계정에도 붙지 못한다 — 호출자가 제공자 계정을 이미 증명했으니 존재를 노출하지 않는다
        if (core.blocks.blocked(null, methodCode, normalized)) {
            core.events.publish(AccountEventType.REGISTRATION_BLOCKED, accountId, detail = mapOf("method" to methodCode, "via" to "link"))
            throw AccountException(AccountErrorCode.REGISTRATION_BLOCKED)
        }
        val identity = Identity(core.newIdentityId(), accountId, methodCode, normalized, verified, metadata = metadata, createdAt = core.time.now())
        // [expectEmailVerified]: 다시 인증이 본 이메일 확인 상태 — 계정 행 락 안에서 같을 때만 붙인다 (그 사이 메일함이 증명됐다면 증명이 지운 수단이 되살아나지 않게)
        val added = if (expectEmailVerified == null) {
            if (core.accounts.addIdentity(identity)) AddIdentityResult.ADDED else AddIdentityResult.DUPLICATE
        } else {
            core.accounts.addIdentityIfEmailVerified(identity, expectEmailVerified)
        }
        when (added) {
            AddIdentityResult.ADDED -> Unit
            AddIdentityResult.STALE -> throw AccountException(AccountErrorCode.REAUTH_FAILED)
            AddIdentityResult.DUPLICATE -> {
                val owner = core.accounts.findIdentity(methodCode, normalized)
                throw AccountException(if (owner?.accountId == accountId) AccountErrorCode.IDENTITY_EXISTS else AccountErrorCode.IDENTITY_TAKEN)
            }
        }
        core.events.publish(AccountEventType.IDENTITY_LINKED, accountId, detail = mapOf("method" to methodCode))
        core.notifyIdentityLinked(accountId, methodCode)
        return list(accountId).first { it.id == identity.id }
    }

    /** 마지막 "들어오는 길" 은 지울 수 없다 — 저장소가 원자적으로 판정한다 */
    fun unlink(accountId: String, identityId: String, currentSessionId: String? = null, input: ReauthInput = ReauthInput()) {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val identity = core.accounts.identitiesOf(accountId).firstOrNull { it.id == identityId } ?: throw AccountException(AccountErrorCode.IDENTITY_NOT_FOUND)
        val method = registry.find(identity.method)
        if (method?.userRemovable == false) throw AccountException(AccountErrorCode.LAST_SIGN_IN_METHOD)
        // 훔친 액세스 토큰 하나로 주인의 로그인 수단을 떼어 내지 못하게 — 연결과 같은 다시 인증
        val limit = core.props.emailChange
        val allowance = core.limits.acquire("unlink:account", accountId, limit.perAccount, limit.perAccountWindow)
        if (!allowance.allowed) throw RateLimitedException(allowance.retryAfterSeconds)
        val proof = Reauth(core).check(account, input, currentSessionId)
        proof.commit()   // 같은 코드로 겹친 두 요청이 둘 다 떼지 못하게 — 떼기 전에 태운다
        when (core.accounts.removeIdentityUnlessLast(accountId, identityId, registry.credentialCodes())) {
            RemoveIdentityResult.REMOVED -> {
                // 그 수단으로 연 세션이 수단보다 오래 살지 않게 — 지금 쓰는 세션만 남기고 닫는다
                core.sessions()?.revokeAll(accountId, currentSessionId)
                core.events.publish(AccountEventType.IDENTITY_UNLINKED, accountId, detail = mapOf("method" to identity.method))
            }
            RemoveIdentityResult.LAST -> throw AccountException(AccountErrorCode.LAST_SIGN_IN_METHOD)
            RemoveIdentityResult.NOT_FOUND -> throw AccountException(AccountErrorCode.IDENTITY_NOT_FOUND)
        }
    }
}
