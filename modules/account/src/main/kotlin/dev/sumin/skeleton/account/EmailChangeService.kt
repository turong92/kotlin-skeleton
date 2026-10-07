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
 * 이메일 변경 — **새 주소를 확인하기 전에는 아무것도 바뀌지 않는다.** 탈취된 세션이 주소를 바꿔 계정을 가로채는 것을 막는 겹겹:
 * 다시 인증(비밀번호 · 메일 코드 · 소셜 코드), 새 주소로 간 6자리 코드를 **요청한 세션에서** 입력, 옛 주소에는 요청 · 변경 두 번 알림이 간다.
 * 새 주소가 남의 것이어도 응답은 같고 아무것도 보내지 않는다 (주소가 쓰이고 있는지 알려 주지 않는다 — 그 요청의 코드는 누구도 모르므로 입력은 끝내 실패한다).
 */
/**
 * 이메일 변경 챌린지의 payload — `<다시 인증이 본 이메일 확인 상태 0|1>:<대상 주소>`. 확인 상태가 요청 때와 입력 때 다르면(그 사이 메일함이 증명됐다) 그 챌린지는 죽은 것이다.
 */
internal object EmailChangePayload {
    fun encode(target: String, emailVerified: Boolean) = (if (emailVerified) "1:" else "0:") + target

    fun target(payload: String) = payload.substringAfter(':')

    fun verifiedSeen(payload: String) = payload.startsWith("1:")
}

class EmailChangeService(private val core: AccountCore) {
    fun request(accountId: String, newEmail: String, input: ReauthInput, sessionId: String?) {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val c = core.props.emailChange
        val a = core.limits.acquire("email-change:account", accountId, c.perAccount, c.perAccountWindow)
        if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)

        val oldEmail = account.email
        val proof = Reauth(core).check(account, input, sessionId)
        val target = Emails.normalize(newEmail)
        if (!Emails.plausible(target)) throw ApplicationException("Invalid email", PlatformErrorCode.VALIDATION_FAILED)
        proof.commit()

        // 대기 중인 변경은 요청 스레드에서 저장한다 — 202 직후의 `GET /me` 가 `pendingEmail` 을 바로 보여 준다.
        // 새 주소가 남의 것이든 아니든, 그 주소의 예산이 남았든 아니든 똑같이 저장한다(요청자에게 보이는 상태가 그것을 알려 주지 않게). 남의 주소 · 예산을 넘은 주소로는 코드가 어디로도 가지 않고,
        // 예산을 넘은 챌린지는 **어떤 코드로도 이길 수 없는 줄**이다 (추측해서 이기면 메일함 증명 없이 주소가 넘어간다).
        // 예산은 가입과 **같은 주소별 버킷**이다 — 계정을 몇 개 만들든 한 메일함에 걸리는 추측 · 메일의 총량이 하나다 (docs/accounts.md)
        val mayOpen = core.mayOpenCodeFor(target)
        val mayMail = core.mayMailCodeTo(target)
        val opened = core.challenges.open(
            ChallengePurposes.EMAIL_CHANGE, account.id, c.ttl, core.props.verification.maxAttempts, accountId = account.id, sessionId = sessionId, payload = EmailChangePayload.encode(target, account.emailVerified), dead = !mayOpen,
        )
        core.tasks.run("email-change-request") {
            // 옛 주소 알림은 새 주소가 쓰이는 중이든 아니든 똑같이 간다 — 로그인한 사용자의 받은편지함이 "그 주소는 가입돼 있다" 를 알려 주지 않게
            oldEmail?.let { core.mailer.send(AccountMail(MailKind.EMAIL_CHANGE_REQUESTED_NOTICE, it, account.locale)) }
            if (target == oldEmail || core.accountByEmail(target) != null || !mayOpen || !mayMail) return@run
            core.mailer.send(AccountMail(MailKind.EMAIL_CHANGE_CODE, target, account.locale, vars = mapOf("code" to opened.code, "minutes" to c.ttl.toMinutes().toString())))
            core.events.publish(AccountEventType.EMAIL_CHANGE_REQUESTED, account.id)
        }
    }

    /** 새 주소로 간 코드를 **요청한 세션에서** 입력한다. 틀리면 [CodeInvalidException], 없음 · 만료 · 소진 · 다른 세션이면 CODE_EXPIRED, 그 사이 주소를 남이 가져갔으면 EMAIL_TAKEN */
    fun confirm(accountId: String, sessionId: String?, code: String, ip: String? = null, ipKey: String? = ip) {
        // 코드 입력도 가입의 코드 입력과 **같은 IP 버킷** · 같은 주소 버킷이다 — 시도를 깎기 전에 센다
        ipKey?.let {
            val v = core.props.verification
            val a = core.limits.acquire("verify:ip", it, v.attemptsPerIp, v.attemptsWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        // 계정을 **먼저** 읽는다 — 이 입력이 본 이메일 확인 상태. 이 뒤에 메일함 증명이 끼어들면 아래 changeEmail 이 계정 행 락 안에서 STALE 로 거른다
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        val open = core.challenges.findOpen(ChallengePurposes.EMAIL_CHANGE, accountId) ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        if (open.sessionId == sessionId) open.payload?.let { core.spendGuess(EmailChangePayload.target(it)) }
        val row = when (val checked = core.challenges.checkOpen(ChallengePurposes.EMAIL_CHANGE, accountId, code, sessionId)) {
            is CodeCheck.Ok -> checked.row
            is CodeCheck.Wrong -> throw if (checked.attemptsLeft <= 0) AccountException(AccountErrorCode.CODE_EXPIRED) else CodeInvalidException(checked.attemptsLeft)
            CodeCheck.Gone -> throw AccountException(AccountErrorCode.CODE_EXPIRED)
        }
        if (!core.challenges.consume(row.id)) throw AccountException(AccountErrorCode.CODE_EXPIRED)
        val payload = row.payload ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        val newEmail = EmailChangePayload.target(payload)
        // 요청한 뒤 메일함이 증명됐다면(재설정 · 가입 코드) 그 요청은 증명 **전의** 계정에서 나온 것이다 — 증명 직후 정리(closeSensitiveLinks)가 닿기 전에도 못 쓴다
        if (EmailChangePayload.verifiedSeen(payload) != account.emailVerified) throw AccountException(AccountErrorCode.CODE_EXPIRED)
        if (account.status != AccountStatus.ACTIVE && account.status != AccountStatus.PENDING_VERIFICATION) throw AccountException(AccountErrorCode.CODE_EXPIRED)
        // 운영자가 지운 계정의 주소로는 바꿀 수 없다 — 메일함을 증명한 뒤에만 드러나는 거절이다 (요청 단계의 응답은 다른 주소와 같다)
        if (core.blocks.blocked(newEmail)) {
            core.events.publish(AccountEventType.REGISTRATION_BLOCKED, account.id, detail = mapOf("via" to "email_change"))
            throw AccountException(AccountErrorCode.REGISTRATION_BLOCKED)
        }
        val oldEmail = account.email
        when (core.accounts.changeEmail(account.id, newEmail, core.time.now(), expectEmailVerified = account.emailVerified)) {
            ChangeEmailResult.TAKEN -> throw AccountException(AccountErrorCode.EMAIL_TAKEN)
            ChangeEmailResult.NOT_FOUND, ChangeEmailResult.STALE -> throw AccountException(AccountErrorCode.CODE_EXPIRED)
            ChangeEmailResult.CHANGED -> Unit
        }
        // 확인한 이 세션은 남는다 (방금 메일함 · 비밀번호로 증명한 쪽) — 나머지는 닫는다. 그 전에 나간 코드 · 링크(옛 주소의 매직 링크 포함)도 함께
        core.sessions()?.revokeAll(account.id, sessionId)
        core.accounts.findById(account.id)?.let { core.closeSensitiveLinks(it, oldEmail) }
        oldEmail?.let { core.mailer.send(AccountMail(MailKind.EMAIL_CHANGED_NOTICE, it, account.locale)) }
        core.events.publish(AccountEventType.EMAIL_CHANGED, account.id)
        // 첫 관리자 부트스트랩은 여기서 일어나지 않는다 — 가입 확인 · 비밀번호 재설정 · 소셜 · 매직 링크 로그인으로 메일함을 증명한 계정에만 (docs/accounts.md)
    }
}
