package dev.sumin.skeleton.account.signin

import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.Identity
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
    fun link(accountId: String, methodCode: String, subject: String, verified: Boolean, metadata: String? = null): IdentityView {
        val method = registry.require(methodCode)
        val normalized = method.normalize(subject)
        if (core.accounts.identitiesOf(accountId).any { it.method == methodCode }) throw AccountException(AccountErrorCode.IDENTITY_EXISTS)
        val identity = Identity(core.newIdentityId(), accountId, methodCode, normalized, verified, metadata = metadata, createdAt = core.time.now())
        if (!core.accounts.addIdentity(identity)) {
            val owner = core.accounts.findIdentity(methodCode, normalized)
            throw AccountException(if (owner?.accountId == accountId) AccountErrorCode.IDENTITY_EXISTS else AccountErrorCode.IDENTITY_TAKEN)
        }
        core.events.publish(AccountEventType.IDENTITY_LINKED, accountId, detail = mapOf("method" to methodCode))
        core.notifyIdentityLinked(accountId, methodCode)
        return list(accountId).first { it.id == identity.id }
    }

    /** 마지막 "들어오는 길" 은 지울 수 없다 — 저장소가 원자적으로 판정한다 */
    fun unlink(accountId: String, identityId: String) {
        val identity = core.accounts.identitiesOf(accountId).firstOrNull { it.id == identityId } ?: throw AccountException(AccountErrorCode.IDENTITY_NOT_FOUND)
        val method = registry.find(identity.method)
        if (method?.userRemovable == false) throw AccountException(AccountErrorCode.LAST_SIGN_IN_METHOD)
        when (core.accounts.removeIdentityUnlessLast(accountId, identityId, registry.credentialCodes())) {
            RemoveIdentityResult.REMOVED -> core.events.publish(AccountEventType.IDENTITY_UNLINKED, accountId, detail = mapOf("method" to identity.method))
            RemoveIdentityResult.LAST -> throw AccountException(AccountErrorCode.LAST_SIGN_IN_METHOD)
            RemoveIdentityResult.NOT_FOUND -> throw AccountException(AccountErrorCode.IDENTITY_NOT_FOUND)
        }
    }
}
