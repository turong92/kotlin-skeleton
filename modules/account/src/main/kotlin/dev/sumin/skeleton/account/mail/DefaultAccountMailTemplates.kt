package dev.sumin.skeleton.account.mail

import dev.sumin.skeleton.account.AccountProperties

/**
 * 내장 ko · en 문구. 계정 로케일로 고르고, 모르는 로케일은 `skeleton.account.mail.default-locale`. 변수 값은 HTML 에서 이스케이프한다.
 * **제목에는 코드 · 링크를 넣지 않는다** — 제목은 로그에 남는다(`LogOnlyMailTransport` · 발송 실패 때의 `SmtpMailSender`), 본문은 남지 않는다.
 */
class DefaultAccountMailTemplates(private val props: AccountProperties.Mail) : AccountMailTemplates {
    private data class Copy(val subject: String, val lines: List<String>, val action: String?)

    override fun render(kind: MailKind, locale: String?, vars: Map<String, String>, link: String?): RenderedMail {
        val lang = pickLocale(locale, props.defaultLocale)
        val copy = (if (lang == "ko") KO else EN).getValue(kind)
        fun fill(s: String) = vars.entries.fold(s) { acc, (k, v) -> acc.replace("{$k}", v) }
        val lines = copy.lines.map(::fill)
        val text = buildString {
            lines.forEach { append(it).append("\n\n") }
            if (link != null) append(link).append("\n\n")
            append(if (lang == "ko") FOOTER_KO else FOOTER_EN)
        }
        val html = buildString {
            append("<div style=\"font-family:sans-serif;line-height:1.5\">")
            lines.forEach { append("<p>").append(escape(it)).append("</p>") }
            if (link != null) append("<p><a href=\"").append(escape(link)).append("\">").append(escape(copy.action ?: link)).append("</a></p>")
            append("<p style=\"color:#666;font-size:12px\">").append(escape(if (lang == "ko") FOOTER_KO else FOOTER_EN)).append("</p></div>")
        }
        return RenderedMail(fill(copy.subject), text.trimEnd(), html)
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        const val FOOTER_EN = "If you did not request this, you can ignore this email."
        const val FOOTER_KO = "직접 요청하지 않았다면 이 메일을 무시해 주세요."

        val EN = mapOf(
            MailKind.VERIFY_CODE to Copy("Your verification code", listOf("Your verification code is {code}.", "Enter it in the sign-up page to finish creating your account. It works {minutes} minutes and only for the page that asked for it.", "Never tell this code to anyone, not even to us."), null),
            MailKind.EMAIL_CHANGE_CODE to Copy("Your code to confirm the new email", listOf("Your confirmation code is {code}.", "Enter it in your account settings, in the browser where you asked for the change, to make this address your account email. It works {minutes} minutes.", "Never tell this code to anyone."), null),
            MailKind.REAUTH_CODE to Copy("Your security code", listOf("Your security code is {code}.", "Enter it where you were asked to confirm it is you (new email, first password or a new sign-in method). It works {minutes} minutes and only in that browser session.", "Never tell this code to anyone."), null),
            MailKind.DELETE_CODE to Copy("Your code to delete the account", listOf("Your confirmation code is {code}.", "Enter it to schedule the deletion of your account. It works {minutes} minutes and only in the browser session that asked for it.", "Never tell this code to anyone."), null),
            MailKind.ALREADY_REGISTERED to Copy("You already have an account", listOf("Someone (hopefully you) tried to sign up with this address, but an account already exists.", "If it was you, sign in or use \"Forgot password\"."), null),
            MailKind.PASSWORD_RESET to Copy("Reset your password", listOf("We received a request to reset your password.", "This link works once and expires in {minutes} minutes. Opening it does not change anything until you choose a new password."), "Choose a new password"),
            MailKind.PASSWORD_CHANGED to Copy("Your password was changed", listOf("The password of your account was just changed and you were signed out on other devices.", "If this was not you, reset your password right away."), null),
            MailKind.EMAIL_CHANGE_REQUESTED_NOTICE to Copy("Email change requested", listOf("A change of your account email was requested. Nothing changes until the new address is confirmed.", "If this was not you, change your password now."), null),
            MailKind.EMAIL_CHANGED_NOTICE to Copy("Your account email was changed", listOf("The email address of your account was changed. You will no longer receive account mail here.", "If this was not you, contact support immediately."), null),
            MailKind.MAGIC_LINK to Copy("Your sign-in link", listOf("Use this link to sign in. It works once and expires in {minutes} minutes."), "Sign in"),
            MailKind.IDENTITY_LINKED_NOTICE to Copy("A sign-in method was added", listOf("A new way to sign in to your account was added: {method}.", "If this was not you, change your password and remove it in your account settings now."), null),
            MailKind.DELETION_SCHEDULED to Copy("Your account is scheduled for deletion", listOf("Your account will be deleted after {days} days. Until then it cannot be used to sign in.", "If this was a mistake, contact support before then."), null),
        )

        val KO = mapOf(
            MailKind.VERIFY_CODE to Copy("인증번호를 보내 드려요", listOf("인증번호는 {code} 예요.", "가입 화면에 입력하면 계정 만들기가 끝나요. {minutes}분 동안만, 그리고 가입을 시작한 그 화면에서만 쓸 수 있어요.", "이 번호를 누구에게도 알려주지 마세요."), null),
            MailKind.EMAIL_CHANGE_CODE to Copy("새 이메일 확인 인증번호", listOf("인증번호는 {code} 예요.", "변경을 요청한 브라우저의 계정 설정 화면에 입력하면 이 주소가 계정 이메일이 돼요. {minutes}분 동안만 쓸 수 있어요.", "이 번호를 누구에게도 알려주지 마세요."), null),
            MailKind.REAUTH_CODE to Copy("보안 인증번호", listOf("인증번호는 {code} 예요.", "본인 확인을 요청한 화면(새 이메일 · 첫 비밀번호 · 새 로그인 수단)에 입력해 주세요. {minutes}분 동안만, 그 브라우저 세션에서만 쓸 수 있어요.", "이 번호를 누구에게도 알려주지 마세요."), null),
            MailKind.DELETE_CODE to Copy("계정 삭제 인증번호", listOf("인증번호는 {code} 예요.", "입력하면 계정 삭제가 예약돼요. {minutes}분 동안만, 요청한 브라우저 세션에서만 쓸 수 있어요.", "이 번호를 누구에게도 알려주지 마세요."), null),
            MailKind.ALREADY_REGISTERED to Copy("이미 계정이 있어요", listOf("이 주소로 가입을 시도한 분이 있어요(본인이라면). 하지만 이미 계정이 있어요.", "본인이라면 로그인하거나 「비밀번호 찾기」를 써 주세요."), null),
            MailKind.PASSWORD_RESET to Copy("비밀번호를 다시 정해 주세요", listOf("비밀번호 재설정 요청을 받았어요.", "이 링크는 한 번만 쓸 수 있고 {minutes}분 뒤에 만료돼요. 열기만 해서는 아무것도 바뀌지 않아요."), "새 비밀번호 정하기"),
            MailKind.PASSWORD_CHANGED to Copy("비밀번호가 바뀌었어요", listOf("계정 비밀번호가 방금 바뀌었고 다른 기기에서는 로그아웃됐어요.", "본인이 아니라면 바로 비밀번호를 다시 정해 주세요."), null),
            MailKind.EMAIL_CHANGE_REQUESTED_NOTICE to Copy("이메일 변경 요청이 있었어요", listOf("계정 이메일을 바꾸려는 요청이 있었어요. 새 주소를 확인하기 전에는 아무것도 바뀌지 않아요.", "본인이 아니라면 지금 비밀번호를 바꿔 주세요."), null),
            MailKind.EMAIL_CHANGED_NOTICE to Copy("계정 이메일이 바뀌었어요", listOf("계정의 이메일 주소가 바뀌었어요. 이제 이 주소로는 계정 메일이 오지 않아요.", "본인이 아니라면 바로 문의해 주세요."), null),
            MailKind.MAGIC_LINK to Copy("로그인 링크예요", listOf("이 링크로 로그인하세요. 한 번만 쓸 수 있고 {minutes}분 뒤에 만료돼요."), "로그인"),
            MailKind.IDENTITY_LINKED_NOTICE to Copy("로그인 수단이 추가됐어요", listOf("계정에 새 로그인 수단이 추가됐어요: {method}.", "본인이 아니라면 지금 비밀번호를 바꾸고 계정 설정에서 그 수단을 지워 주세요."), null),
            MailKind.DELETION_SCHEDULED to Copy("계정 삭제가 예약됐어요", listOf("{days}일 뒤에 계정이 삭제돼요. 그 전까지는 로그인할 수 없어요.", "실수였다면 그 전에 문의해 주세요."), null),
        )
    }
}
