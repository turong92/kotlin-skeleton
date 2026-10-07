package dev.sumin.skeleton.account

import java.time.Instant

/**
 * 단일 인스턴스 · 시험용 기본 저장소 — 재시작하면 모든 계정이 사라진다 (stage · prod 의 DeployGuard 가 문제로 본다: `account-jdbc` 를 얹는다).
 * [AccountRepository] 의 원자성 약속을 하나의 락으로 지킨다.
 */
class InMemoryAccountRepository : AccountRepository {
    private val accounts = LinkedHashMap<String, Account>()
    private val identities = LinkedHashMap<String, Identity>()
    private val claimed = HashSet<String>()   // 지우기를 선점한 계정 ([claimErasure])

    @Synchronized override fun insert(account: Account, identities: List<Identity>): Boolean {
        if (account.id in accounts) return false
        if (account.email != null && accounts.values.any { it.email == account.email }) return false
        if (identities.any { n -> this.identities.values.any { it.method == n.method && it.subject == n.subject } }) return false
        accounts[account.id] = account
        identities.forEach { this.identities[it.id] = it }
        return true
    }

    @Synchronized override fun findById(id: String): Account? = accounts[id]

    @Synchronized override fun findByEmail(email: String): Account? = accounts.values.firstOrNull { it.email == email }

    @Synchronized override fun findByIdentity(method: String, subject: String): Account? =
        identities.values.firstOrNull { it.method == method && it.subject == subject }?.let { accounts[it.accountId] }

    @Synchronized override fun update(id: String, patch: AccountPatch, now: Instant): Account? {
        val a = accounts[id]?.takeIf { it.status != AccountStatus.ERASED } ?: return null   // 지운 행에는 아무것도 다시 쓰지 않는다
        if (patch.status != null && id in claimed) return null                              // 지우기를 선점한 계정의 상태는 바꾸지 않는다
        val updated = a.copy(
            displayName = patch.displayName ?: a.displayName,
            locale = patch.locale ?: a.locale,
            timeZone = patch.timeZone ?: a.timeZone,
            status = patch.status ?: a.status,
            lastLoginAt = patch.lastLoginAt ?: a.lastLoginAt,
            suspendedReason = if (patch.clearSuspendedReason) null else patch.suspendedReason ?: a.suspendedReason,
            deletedAt = if (patch.clearDeletion) null else patch.deletedAt ?: a.deletedAt,
            purgeAfter = if (patch.clearDeletion) null else patch.purgeAfter ?: a.purgeAfter,
            updatedAt = now,
        )
        accounts[id] = updated
        return updated
    }

    @Synchronized override fun markEmailVerified(id: String, now: Instant): Boolean {
        val a = accounts[id] ?: return false
        val email = a.email ?: return false
        accounts[id] = a.copy(emailVerified = true, status = if (a.status == AccountStatus.PENDING_VERIFICATION) AccountStatus.ACTIVE else a.status, updatedAt = now)
        identities.values.filter { it.accountId == id && it.subject == email }.forEach { identities[it.id] = it.copy(verified = true) }
        return true
    }

    @Synchronized override fun proveMailbox(id: String, now: Instant, proof: MailboxProof): Boolean {
        val a = accounts[id] ?: return false
        val email = a.email ?: return false
        // 미확인 계정에서는 새 비밀번호를 정하면 옛 비밀번호 수단 행도 지운다(id 가 바뀐다) — 그 행의 id 를 들고 있던 남의 뒤늦은 갱신이 새 비밀번호를 덮지 못하게
        if (!a.emailVerified) {
            identities.values.filter { it.accountId == id && (it.id !in proof.keepIdentityIds || (proof.passwordSecret != null && it.method == SignInMethods.PASSWORD)) }.forEach { identities.remove(it.id) }
        }
        proof.passwordSecret?.let { secret ->
            val existing = identities.values.firstOrNull { it.accountId == id && it.method == SignInMethods.PASSWORD && it.subject == email }
            if (existing != null) identities[existing.id] = existing.copy(secret = secret)
            else {
                val taken = identities.values.any { it.method == SignInMethods.PASSWORD && it.subject == email }
                if (!taken) {
                    val newId = requireNotNull(proof.newPasswordIdentityId) { "newPasswordIdentityId" }
                    identities[newId] = Identity(newId, id, SignInMethods.PASSWORD, email, true, secret = secret, createdAt = now)
                }
            }
        }
        return markEmailVerified(id, now)
    }

    @Synchronized override fun changeEmail(id: String, newEmail: String, now: Instant, expectEmailVerified: Boolean?): ChangeEmailResult {
        val a = accounts[id]?.takeIf { it.status != AccountStatus.ERASED } ?: return ChangeEmailResult.NOT_FOUND
        if (expectEmailVerified != null && a.emailVerified != expectEmailVerified) return ChangeEmailResult.STALE
        if (accounts.values.any { it.id != id && it.email == newEmail }) return ChangeEmailResult.TAKEN
        val moving = identities.values.filter { it.accountId == id && it.subject == a.email }
        if (moving.any { m -> identities.values.any { it.id != m.id && it.method == m.method && it.subject == newEmail } }) return ChangeEmailResult.TAKEN
        accounts[id] = a.copy(email = newEmail, emailVerified = true, updatedAt = now)
        moving.forEach { identities[it.id] = it.copy(subject = newEmail, verified = true) }
        return ChangeEmailResult.CHANGED
    }

    @Synchronized override fun grantRole(id: String, role: String, now: Instant): Boolean {
        val a = accounts[id] ?: return false
        if (role in a.roles || a.status == AccountStatus.ERASED) return false
        accounts[id] = a.copy(roles = a.roles + role, updatedAt = now)
        return true
    }

    @Synchronized override fun revokeRole(id: String, role: String, now: Instant): Boolean {
        val a = accounts[id] ?: return false
        if (role !in a.roles) return false
        accounts[id] = a.copy(roles = a.roles - role, updatedAt = now)
        return true
    }

    @Synchronized override fun countActiveWithRole(role: String): Long =
        accounts.values.count { it.status == AccountStatus.ACTIVE && role in it.roles }.toLong()

    @Synchronized override fun search(email: String?, status: AccountStatus?, page: Int, size: Int): AccountPage {
        val all = accounts.values.filter { (email == null || it.email?.contains(email.lowercase()) == true) && (if (status == null) it.status != AccountStatus.ERASED else it.status == status) }
            .sortedWith(compareByDescending<Account> { it.createdAt }.thenBy { it.id })
        return AccountPage(all.drop(page * size).take(size), all.size.toLong())
    }

    @Synchronized override fun dueForPurge(now: Instant, limit: Int): List<Account> =
        accounts.values.filter { it.status == AccountStatus.DELETED && it.purgeAfter != null && !it.purgeAfter.isAfter(now) }.sortedBy { it.purgeAfter }.take(limit)

    @Synchronized override fun purge(id: String, now: Instant, forced: Boolean): Boolean {
        val a = accounts[id] ?: return false
        if (!eligible(a, now, forced)) return false
        identities.values.removeIf { it.accountId == id }
        claimed.remove(id)
        return accounts.remove(id) != null
    }

    private fun eligible(a: Account, now: Instant, forced: Boolean) =
        if (forced) a.status == AccountStatus.SUSPENDED else a.status == AccountStatus.DELETED && a.purgeAfter != null && !a.purgeAfter.isAfter(now)

    @Synchronized override fun erase(id: String, now: Instant, forced: Boolean): Boolean {
        val a = accounts[id] ?: return false
        if (!eligible(a, now, forced)) return false
        identities.values.removeIf { it.accountId == id }
        claimed.remove(id)
        accounts[id] = a.copy(
            email = null, emailVerified = false, status = AccountStatus.ERASED, roles = emptySet(), displayName = null, locale = null, timeZone = null,
            lastLoginAt = null, suspendedReason = null, purgeAfter = null, erasedAt = now, updatedAt = now,
        )
        return true
    }

    @Synchronized override fun claimErasure(id: String, now: Instant, forced: Boolean): Boolean {
        val a = accounts[id] ?: return false
        if (!eligible(a, now, forced)) return false
        claimed += id
        return true
    }

    @Synchronized override fun restore(id: String, status: AccountStatus, now: Instant): Boolean {
        val a = accounts[id] ?: return false
        if (id in claimed) return false
        if (a.status != AccountStatus.DELETED || a.purgeAfter == null || !a.purgeAfter.isAfter(now)) return false
        accounts[id] = a.copy(status = status, deletedAt = null, purgeAfter = null, updatedAt = now)
        return true
    }

    @Synchronized override fun updateUnlessLast(id: String, patch: AccountPatch, now: Instant, guardRole: String): GuardedResult {
        val a = accounts[id]?.takeIf { it.status != AccountStatus.ERASED } ?: return GuardedResult.NOT_FOUND
        val leavesActive = patch.status != null && patch.status != AccountStatus.ACTIVE
        if (leavesActive && a.status == AccountStatus.ACTIVE && guardRole in a.roles && countActiveWithRole(guardRole) <= 1) return GuardedResult.LAST
        if (update(id, patch, now) == null) return GuardedResult.NOT_FOUND
        return GuardedResult.DONE
    }

    @Synchronized override fun revokeRoleUnlessLast(id: String, role: String, now: Instant): GuardedResult {
        val a = accounts[id] ?: return GuardedResult.NOT_FOUND
        if (role !in a.roles) return GuardedResult.NOT_FOUND
        if (a.status == AccountStatus.ACTIVE && countActiveWithRole(role) <= 1) return GuardedResult.LAST
        revokeRole(id, role, now)
        return GuardedResult.DONE
    }

    @Synchronized override fun addIdentity(identity: Identity): Boolean {
        if (accounts[identity.accountId]?.status.let { it == null || it == AccountStatus.ERASED }) return false
        if (identities.values.any { it.method == identity.method && it.subject == identity.subject }) return false
        identities[identity.id] = identity
        return true
    }

    @Synchronized override fun addIdentityIfEmailVerified(identity: Identity, expectEmailVerified: Boolean): AddIdentityResult =
        if (accounts[identity.accountId]?.takeIf { it.status != AccountStatus.ERASED }?.emailVerified != expectEmailVerified) AddIdentityResult.STALE
        else if (addIdentity(identity)) AddIdentityResult.ADDED else AddIdentityResult.DUPLICATE

    @Synchronized override fun findIdentity(method: String, subject: String): Identity? = identities.values.firstOrNull { it.method == method && it.subject == subject }

    @Synchronized override fun findIdentityById(id: String): Identity? = identities[id]

    @Synchronized override fun identitiesOf(accountId: String): List<Identity> = identities.values.filter { it.accountId == accountId }.sortedBy { it.createdAt }

    @Synchronized override fun removeIdentity(accountId: String, identityId: String): Boolean {
        val i = identities[identityId] ?: return false
        if (i.accountId != accountId) return false
        identities.remove(identityId)
        return true
    }

    @Synchronized override fun removeIdentityUnlessLast(accountId: String, identityId: String, credentialMethods: Collection<String>): RemoveIdentityResult {
        val target = identities[identityId]?.takeIf { it.accountId == accountId } ?: return RemoveIdentityResult.NOT_FOUND
        val others = identities.values.count { it.accountId == accountId && it.id != identityId && it.method in credentialMethods }
        if (target.method in credentialMethods && others == 0) return RemoveIdentityResult.LAST
        identities.remove(identityId)
        return RemoveIdentityResult.REMOVED
    }

    @Synchronized override fun updateIdentitySecret(identityId: String, secret: String?, expectedSecret: String?): Boolean {
        val i = identities[identityId] ?: return false
        if (expectedSecret != null && i.secret != expectedSecret) return false
        identities[identityId] = i.copy(secret = secret)
        return true
    }

    @Synchronized override fun touchIdentity(identityId: String, now: Instant) {
        identities[identityId]?.let { identities[identityId] = it.copy(lastUsedAt = now) }
    }
}
