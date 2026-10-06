package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * 계정 삭제 요청 — **다시 인증**해야 한다(비밀번호가 있으면 비밀번호, 없으면 메일로 받은 한 번 쓰는 확인 링크).
 * 요청하면 곧바로 로그인이 막히고(DELETED, 세션 닫힘) 데이터는 `deletion.grace` 뒤에야 지워진다 ([AccountPurgeService]).
 * 그 사이 관리자는 복구할 수 있다.
 */
class DeletionService(private val core: AccountCore) {
    /** 비밀번호가 없는 계정이 쓸 6자리 확인 코드를 메일로 보낸다 — 요청한 세션에서만 쓸 수 있다 (주소가 없으면 조용히) */
    fun requestConfirmation(accountId: String, sessionId: String?) {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
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
}

/**
 * 삭제 유예가 끝난 계정을 지운다. 계정 id 를 들고 있는 모듈들의 [AccountErasureListener] 를 **모두** 부른 뒤에야 계정 행을 지운다 —
 * 하나라도 실패하면 그 계정은 그대로 두고(반쯤 지워진 상태를 만들지 않는다) 다음 주기에 다시 한다.
 */
class AccountPurgeService(
    private val core: AccountCore,
    private val listeners: () -> List<AccountErasureListener>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object { val TOKEN_RETENTION: java.time.Duration = java.time.Duration.ofDays(1) }

    /** 이번에 지운 계정 수 */
    fun purgeDue(): Int {
        val due = core.accounts.dueForPurge(core.time.now(), core.props.deletion.purgeBatch)
        var purged = 0
        for (account in due) {
            // 다시 읽어 아직 지울 계정인지 확인하고 (그 사이 되살려졌으면 건너뛴다), 지울 때도 저장소가 조건으로 한 번 더 막는다
            val now = core.time.now()
            val current = core.accounts.findById(account.id)
            if (current == null || current.status != AccountStatus.DELETED || current.purgeAfter?.isAfter(now) != false) continue
            val request = ErasureRequest(account.id, AccountTombstone.of(account.id))
            val failed = listeners().firstOrNull { listener ->
                try { listener.erase(request); false } catch (e: Exception) {
                    log.warn("erasure listener '{}' failed for an account; it stays for the next run: {}", listener.name, e.javaClass.simpleName); true
                }
            }
            if (failed != null) continue
            if (core.accounts.purge(account.id, now)) {
                purged++
                core.events.publish(AccountEventType.ACCOUNT_PURGED, account.id)
            }
        }
        core.tokens.sweep(TOKEN_RETENTION)   // 지난 한 번 쓰는 토큰 · 코드 줄도 같이 청소
        core.challenges.sweep(TOKEN_RETENTION)
        return purged
    }
}
