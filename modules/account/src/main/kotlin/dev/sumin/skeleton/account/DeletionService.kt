package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.TokenPurposes
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * 계정 삭제 요청 — **다시 인증**해야 한다(비밀번호가 있으면 비밀번호, 없으면 메일로 받은 6자리 코드 · 주소가 없으면 소셜 인가 코드).
 * 요청하면 곧바로 로그인이 막히고(DELETED, 세션 닫힘) 데이터는 `deletion.grace` 뒤에야 지워진다 ([AccountPurgeService]).
 * 그 사이 관리자는 복구할 수 있다.
 */
class DeletionService(private val core: AccountCore) {
    /** 비밀번호가 없는 계정이 쓸 6자리 확인 코드를 메일로 보낸다 — 요청한 세션에서만 쓸 수 있다 (주소가 없으면 조용히) */
    fun requestConfirmation(accountId: String, sessionId: String?) {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        if (account.status == AccountStatus.SUSPENDED) throw AccountException(AccountErrorCode.SUSPENDED_CANNOT_DELETE)
        val c = core.props.deletion
        val e = core.props.emailChange
        val a = core.limits.acquire("delete-confirmation:account", accountId, e.perAccount, e.perAccountWindow)
        if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        val email = account.email ?: return
        val opened = core.challenges.open(ChallengePurposes.DELETE_CONFIRM, accountId, c.confirmationTtl, core.props.verification.maxAttempts, accountId = accountId, sessionId = sessionId)
        core.tasks.run("delete-confirmation") {
            core.mailer.send(AccountMail(MailKind.DELETE_CODE, email, account.locale, vars = mapOf("code" to opened.code, "minutes" to c.confirmationTtl.toMinutes().toString())))
        }
    }

    /** 삭제를 예약하고 지워질 시각을 돌려준다. 이미 삭제 중이면 같은 시각(유예를 늘리지 않는다) */
    fun delete(accountId: String, input: ReauthInput, sessionId: String?): Instant {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        if (account.status == AccountStatus.DELETED) return account.purgeAfter ?: core.time.now()
        if (account.status == AccountStatus.SUSPENDED) throw AccountException(AccountErrorCode.SUSPENDED_CANNOT_DELETE)   // 정지는 박제 — 탈퇴로 빠져나가지 못한다
        val login = core.props.login
        val allowance = core.limits.acquire("reauth:account", account.id, login.perAccount, login.window)
        if (!allowance.allowed) throw RateLimitedException(allowance.retryAfterSeconds)
        val proof = Reauth(core).check(account, input, sessionId, ChallengePurposes.DELETE_CONFIRM, AccountErrorCode.REAUTH_FAILED)

        val now = core.time.now()
        val purgeAfter = now.plus(core.props.deletion.grace)
        when (core.accounts.updateUnlessLast(accountId, AccountPatch(status = AccountStatus.DELETED, deletedAt = now, purgeAfter = purgeAfter), now, core.props.admin.role)) {
            GuardedResult.LAST -> throw AccountException(AccountErrorCode.LAST_ADMIN)
            GuardedResult.NOT_FOUND -> throw AccountException(AccountErrorCode.NOT_FOUND)
            GuardedResult.DONE -> Unit
        }
        proof.commit()
        core.sessions()?.revokeAll(accountId, null)
        account.email?.let { core.mailer.send(AccountMail(MailKind.DELETION_SCHEDULED, it, account.locale, vars = mapOf("days" to core.props.deletion.grace.toDays().toString()))) }
        core.events.publish(AccountEventType.DELETION_SCHEDULED, accountId, detail = mapOf("purgeAfter" to purgeAfter.toString()))
        return purgeAfter
    }

    /**
     * 탈퇴 취소 (`deletion.self-restore`) — 로그인에 성공한 주인이 받은 한 번 쓰는 토큰으로 계정을 ACTIVE(미확인 이메일이면 PENDING_VERIFICATION)로 되돌린다.
     * 토큰이 없거나 · 만료 · 이미 씀 · 다른 용도 · 유예가 끝남 · 그 사이 정지됨이면 모두 [AccountErrorCode.TOKEN_INVALID] 하나다. 주소당 시도 수를 센다.
     * 돌려받은 [AuthAccount] 로 호출자가 보통 토큰을 발급한다.
     */
    fun cancel(restoreToken: String, ip: String?, ipKey: String? = ip): dev.sumin.skeleton.auth.account.AuthAccount {
        ipKey?.let {
            val l = core.props.login
            val a = core.limits.acquire("delete-cancel:ip", it, l.perIp, l.window)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        if (!core.props.deletion.selfRestore) throw AccountException(AccountErrorCode.TOKEN_INVALID)
        val grant = core.tokens.consume(TokenPurposes.DELETION_RESTORE, restoreToken) ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
        val account = grant.accountId?.let(core.accounts::findById) ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
        val now = core.time.now()
        val reopened = if (account.emailVerified || account.email == null) AccountStatus.ACTIVE else AccountStatus.PENDING_VERIFICATION
        // 조건부 갱신 한 문장: DELETED 이고 유예가 안 끝났을 때만 — 정지 · 지움과 동시에 둘 다 이기지 못한다
        if (!core.accounts.restore(account.id, reopened, now)) throw AccountException(AccountErrorCode.TOKEN_INVALID)
        account.email?.let { core.mailer.send(AccountMail(MailKind.DELETION_CANCELLED, it, account.locale)) }
        core.events.publish(AccountEventType.DELETION_CANCELLED, account.id, ip)
        return AccountAuthRepository(core).toAuth(core.accounts.findById(account.id) ?: account) ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
    }
}

/**
 * 삭제 유예가 끝난 계정을 정리한다 (`deletion.mode`: ANONYMIZE = 행을 남기고 개인정보만 지움 · DELETE = 행까지 지움).
 * 계정 id 를 들고 있는 모듈들의 [AccountErasureListener] 를 **모두** 부른 뒤에야 계정을 건드린다 —
 * 하나라도 실패하면 그 계정은 그대로 두고(반쯤 지워진 상태를 만들지 않는다) 다음 주기에 다시 한다. 한 계정의 실패가 같은 묶음의 다른 계정을 막지 않는다.
 */
class AccountPurgeService(
    private val core: AccountCore,
    private val listeners: () -> List<AccountErasureListener>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이번에 정리한 계정 수. 다른 실행이 임대를 쥐고 있으면(여러 인스턴스 · 겹친 실행) 아무것도 하지 않고 0 */
    fun purgeDue(): Int {
        val owner = core.lease.tryAcquire(MaintenanceLeases.PURGE_RUN, MaintenanceLeases.RUN_TTL) ?: return 0
        try { return purgeDueLocked() } finally { core.lease.release(MaintenanceLeases.PURGE_RUN, owner) }
    }

    private fun purgeDueLocked(): Int {
        val due = core.accounts.dueForPurge(core.time.now(), core.props.deletion.purgeBatch)
        var purged = 0
        for (account in due) {
            try {
                if (purgeOne(account)) purged++
            } catch (e: Exception) {
                log.warn("purging an account failed; it stays for the next run: {}", e.javaClass.simpleName)
            }
        }
        val retention = core.props.cleanup.expiredRetention
        core.blocks.sweep()   // 만료된 재가입 차단
        core.tokens.sweep(retention)   // 지난 한 번 쓰는 토큰 · 코드 줄도 같이 청소
        core.challenges.sweep(retention)
        return purged
    }

    private fun purgeOne(account: Account): Boolean {
        // 다시 읽어 아직 지울 계정인지 확인하고 (그 사이 되살려졌으면 건너뛴다), 지울 때도 저장소가 조건으로 한 번 더 막는다
        val now = core.time.now()
        val current = core.accounts.findById(account.id)
        if (current == null || current.status != AccountStatus.DELETED || current.purgeAfter?.isAfter(now) != false) return false
        val keep = core.props.deletion.mode == AccountProperties.Deletion.Mode.ANONYMIZE
        val request = ErasureRequest(account.id, AccountTombstone.of(account.id), accountKept = keep)
        if (!runListeners(request)) return false
        core.closeSensitiveLinks(current)   // 저장소가 토큰을 같이 지우지 못하는 구현(메모리 · 앱 구현)을 위해
        val done = if (keep) core.accounts.erase(account.id, now) else core.accounts.purge(account.id, now)
        if (done) core.events.publish(AccountEventType.ACCOUNT_PURGED, account.id)
        return done
    }

    /** 모든 고리를 부른다 — 하나라도 실패하면 false (나머지는 부르지 않는다) */
    private fun runListeners(request: ErasureRequest): Boolean =
        listeners().firstOrNull { listener ->
            try { listener.erase(request); false } catch (e: Exception) {
                log.warn("erasure listener '{}' failed for an account; it stays for the next run: {}", listener.name, e.javaClass.simpleName); true
            }
        } == null

    /**
     * 운영자가 **정지된** 계정을 유예 없이 지운다 — 같은 고리들을 부르고, 이메일 · 로그인 수단의 재가입 차단(해시)을 남기고, 계정을 지운다(`deletion.mode` 대로).
     * 정지가 아니면 [AccountErrorCode.NOT_SUSPENDED], 이미 지웠으면 [AccountErrorCode.ERASED]. 고리가 실패하면 예외 — 계정은 그대로이고 다시 부르면 된다 (멱등).
     */
    fun eraseSuspended(actorId: String, accountId: String, reason: String?): Boolean {
        if (actorId == accountId) throw AccountException(AccountErrorCode.SELF_ACTION_FORBIDDEN)
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        if (account.status == AccountStatus.ERASED) throw AccountException(AccountErrorCode.ERASED)
        if (account.status != AccountStatus.SUSPENDED) throw AccountException(AccountErrorCode.NOT_SUSPENDED)
        val identities = core.accounts.identitiesOf(accountId)
        val keep = core.props.deletion.mode == AccountProperties.Deletion.Mode.ANONYMIZE
        check(runListeners(ErasureRequest(accountId, AccountTombstone.of(accountId), accountKept = keep))) { "an erasure listener failed; the account was left as it was" }
        core.blocks.add(account, identities, reason, actorId)   // 지우기 전에 — 지운 뒤에는 이메일 · 주체를 알 수 없다
        core.closeSensitiveLinks(account)
        val now = core.time.now()
        val done = if (keep) core.accounts.erase(accountId, now, forced = true) else core.accounts.purge(accountId, now, forced = true)
        if (done) {
            core.events.publish(AccountEventType.ACCOUNT_ERASED_BY_ADMIN, accountId, detail = mapOf("by" to actorId))
            core.events.publish(AccountEventType.REGISTRATION_BLOCK_ADDED, accountId, detail = mapOf("by" to actorId))
        }
        return done
    }
}
