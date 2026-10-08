package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.challenge.CodeCheck
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode

/**
 * 연락받을 이메일 — 계정 이메일과 **별개의** 선택 주소 하나 (서비스 안내를 받을 곳). 로그인 수단도 · 복구 수단도 · 중복 판정의 키도 아니다:
 * 유니크하지 않고(다른 계정의 이메일 · 연락 이메일과 같아도 된다 — 그래서 "이미 쓰인 주소" 신호가 없다), 계정을 찾는 어떤 조회도 이 열을 보지 않는다.
 * 주소 소유를 증명하는 6자리 코드는 계정 이메일 변경과 같은 규칙(챌린지 · 10분 · 5번 · 요청한 **세션에서만** · 주소별 예산 공유)이고 재인증은 요구하지 않는다 —
 * 이 주소는 계정을 열지 못하므로 일반 로그인 상태로 충분하다. 대신 등록 · 변경 · 삭제는 **계정 이메일**로 알림이 한 통 간다.
 */
class ContactEmailService(private val core: AccountCore) {
    /** 코드는 [address](연락 이메일 후보)로 간다. 응답 시각은 메일이 나갔든 예산을 넘었든 같은 계산 */
    fun request(accountId: String, address: String, sessionId: String?): CodeWindow {
        val account = core.accounts.findById(accountId)?.takeIf { it.status != AccountStatus.ERASED } ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val c = core.props.emailChange
        val a = core.limits.acquire("contact-email:account", accountId, c.perAccount, c.perAccountWindow)
        if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        val target = Emails.normalize(address)
        if (!Emails.plausible(target)) throw ApplicationException("Invalid email", PlatformErrorCode.VALIDATION_FAILED)

        val mayOpen = core.mayOpenCodeFor(target)
        val mayMail = core.mayMailCodeTo(target)
        val opened = core.challenges.open(
            ChallengePurposes.CONTACT_EMAIL, accountId, c.ttl, core.props.verification.maxAttempts, accountId = accountId, sessionId = sessionId, payload = target, dead = !mayOpen,
        )
        core.tasks.run("contact-email-request") {
            if (!mayOpen || !mayMail) return@run
            core.mailer.send(AccountMail(MailKind.CONTACT_EMAIL_CODE, target, account.locale, vars = mapOf("code" to opened.code, "minutes" to c.ttl.toMinutes().toString())))
        }
        return core.codeWindow(c.ttl)
    }

    /** 코드를 **요청한 세션에서** 입력한다. 틀리면 [CodeInvalidException], 없음 · 만료 · 소진 · 다른 세션이면 CODE_EXPIRED */
    fun confirm(accountId: String, sessionId: String?, code: String, ip: String? = null, ipKey: String? = ip) {
        ipKey?.let {
            val v = core.props.verification
            val a = core.limits.acquire("verify:ip", it, v.attemptsPerIp, v.attemptsWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        val account = core.accounts.findById(accountId)?.takeIf { it.status != AccountStatus.ERASED } ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        val open = core.challenges.findOpen(ChallengePurposes.CONTACT_EMAIL, accountId) ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        if (open.sessionId == sessionId) open.payload?.let { core.spendGuess(it) }
        val row = when (val checked = core.challenges.checkOpen(ChallengePurposes.CONTACT_EMAIL, accountId, code, sessionId)) {
            is CodeCheck.Ok -> checked.row
            is CodeCheck.Wrong -> throw if (checked.attemptsLeft <= 0) AccountException(AccountErrorCode.CODE_EXPIRED) else CodeInvalidException(checked.attemptsLeft)
            CodeCheck.Gone -> throw AccountException(AccountErrorCode.CODE_EXPIRED)
        }
        if (!core.challenges.consume(row.id)) throw AccountException(AccountErrorCode.CODE_EXPIRED)
        val target = row.payload ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        // 운영자가 지운 주소도 연락 주소로는 쓸 수 있다 — 등록(가입)이 아니고, 이 주소로는 로그인도 계정 복구도 못 한다 (docs/accounts.md)
        core.accounts.update(accountId, AccountPatch(contactEmail = target), core.time.now()) ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        changed(account, if (account.contactEmail == null) "registered" else "changed")
    }

    /** 연락 이메일을 지운다 (이후 서비스 안내는 계정 이메일로). 없으면 조용히 끝난다 */
    fun remove(accountId: String) {
        val account = core.accounts.findById(accountId)?.takeIf { it.status != AccountStatus.ERASED } ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        if (account.contactEmail == null) return
        core.accounts.update(accountId, AccountPatch(clearContactEmail = true), core.time.now())
        changed(account, "removed")
    }

    private fun changed(account: Account, via: String) {
        account.email?.let { core.mailer.send(AccountMail(MailKind.CONTACT_EMAIL_NOTICE, it, account.locale)) }
        core.events.publish(AccountEventType.CONTACT_EMAIL_CHANGED, account.id, detail = mapOf("via" to via))
    }
}
