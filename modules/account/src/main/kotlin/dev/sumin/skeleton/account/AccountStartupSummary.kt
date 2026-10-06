package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.mail.AccountMailTransport
import dev.sumin.skeleton.account.mail.LogOnlyMailTransport
import dev.sumin.skeleton.account.web.SocialMethodView

/** 기동할 때 한 줄: 어떤 로그인 방법과 메일 길이 켜졌나 — 비밀(client secret · SMTP 비밀번호)은 물론 client id 도 싣지 않는다(있는지만) */
object AccountStartupSummary {
    fun describe(methods: List<String>, social: List<SocialMethodView>, transport: AccountMailTransport, smtpHost: String?, from: String?, mail: AccountProperties.Mail): String {
        val socialText = if (social.isEmpty()) "none" else social.joinToString(", ") { "${it.provider}(clientId=${if (it.clientId.isNullOrBlank()) "MISSING" else "set"}, redirectUri=${it.redirectUri ?: "none"})" }
        val mailText = when {
            transport is LogOnlyMailTransport -> "NOT SENT (log only — add notification-mail + SMTP settings to deliver)"
            smtpHost != null -> "SMTP $smtpHost from=${from ?: "?"}"
            else -> "custom transport ${transport.javaClass.simpleName}"
        }
        return "account sign-in methods: ${methods.joinToString(", ").ifEmpty { "none" }}; social: $socialText; mail: $mailText; html=${if (mail.htmlEnabled) "on" else "off"}; link-base-url=${mail.linkBaseUrl.ifBlank { "(unset)" }}"
    }
}
