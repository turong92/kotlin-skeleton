package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind

/**
 * 아이디 찾기 (스펙 §10) — [PasswordService.forgot] 과 한 글자씩 같은 틀이다: 요청 스레드는 IP 한도와 캡차만 보고, 주소 조회 · 한도 · 메일은 뒤에서 한다.
 * 그래서 응답과 응답 시점이 계정 존재와 무관하다 (넘으면 IP 한도의 429 만 다르다).
 *
 * 한도의 **값**은 `reset.per-ip` · `reset.per-email` 을 쓰되 버킷은 따로다 — 비밀번호 재설정 요청이 아이디 찾기의 몫을 쓰지 않고, 그 반대도 같다.
 * 메일은 **계정 이메일로만** 간다 (연락받을 이메일로는 가지 않고, 그 주소로 계정을 찾지도 않는다). 아이디는 메일 본문에만 있다 — 이벤트 · 로그에는 싣지 않는다.
 */
class LoginIdReminderService(private val core: AccountCore) {
    fun forgot(email: String, ip: String?, captchaToken: String?, ipKey: String? = ip) {
        ipKey?.let {
            val r = core.props.reset
            val a = core.limits.acquire("login-id-forgot:ip", it, r.perIp, r.perIpWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        core.captcha.check(captchaToken, ip, "login_id_forgot")
        val normalized = Emails.normalize(email)
        core.tasks.run("login-id-forgot") { sendReminder(normalized, ip) }
    }

    private fun sendReminder(email: String, ip: String?) {
        val account = core.accountByEmail(email) ?: return
        // 정지 · 탈퇴한 계정에는 아무것도 보내지 않는다 (재설정과 같다)
        if (account.status != AccountStatus.ACTIVE && account.status != AccountStatus.PENDING_VERIFICATION) return
        val to = account.email ?: return
        val r = core.props.reset
        if (!core.limits.acquire("login-id-forgot:email", to, r.perEmail, r.perEmailWindow).allowed) return   // 조용히
        // 링크는 로그인 페이지 — 토큰이 없다. 아이디를 아직 안 정한 계정은 아이디 없이 간다 (다른 본문)
        val vars = account.loginId?.let { mapOf("loginId" to it) } ?: emptyMap()
        core.mailer.send(AccountMail(MailKind.LOGIN_ID_REMINDER, to, account.locale, core.links.login(), vars))
        core.events.publish(AccountEventType.LOGIN_ID_REMINDER_SENT, account.id, ip)
    }
}
