package dev.sumin.skeleton.workspace.mail

import dev.sumin.skeleton.common.logging.LogMasker
import dev.sumin.skeleton.workspace.InvitationMail
import dev.sumin.skeleton.workspace.WorkspaceInvitationMailer
import dev.sumin.skeleton.workspace.WorkspaceRole
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.slf4j.LoggerFactory

data class RenderedMail(val subject: String, val text: String)

/** 앱이 문구 · 브랜드를 바꾸려면 이 빈을 두면 된다 (기본: ko · en 내장). [link] 는 토큰이 들어 있는 수락 주소 */
fun interface WorkspaceInvitationMail {
    fun render(mail: InvitationMail, link: String): RenderedMail
}

/** 실제로 보내는 길 — `notification-mail` 이 있으면 그 `MailSender` 로, 없으면 로그만([LogOnlyWorkspaceMailTransport]) */
fun interface WorkspaceMailTransport {
    /** 받아들여졌으면 true */
    fun send(to: String, subject: String, text: String): Boolean
}

/** 메일 링크 `<link-base-url>/invite?token=…` — 프로젝트 모드에서는 부를 때마다 그 프로젝트의 주소를 읽는다 */
class WorkspaceLinks(private val baseUrl: () -> String) {
    fun invite(token: String): String = baseUrl().trimEnd('/') + "/invite?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
}

class TemplatedWorkspaceInvitationMailer(
    private val templates: WorkspaceInvitationMail,
    private val transport: WorkspaceMailTransport,
    private val links: WorkspaceLinks,
) : WorkspaceInvitationMailer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(mail: InvitationMail) {
        val rendered = templates.render(mail, links.invite(mail.token))
        val subject = rendered.subject.replace(Regex("[\\r\\n]+"), " ")   // 제목은 한 줄이다
        val accepted = try { transport.send(mail.to, subject, rendered.text) } catch (e: Exception) {
            log.warn("workspace invitation mail failed: {}", e.javaClass.simpleName); false
        }
        if (!accepted) log.warn("workspace invitation mail was not accepted by the transport; the invitation stays and can be resent")
    }
}

/**
 * 메일 모듈이 없을 때의 길 — 보내지 않고 알린다. 링크(토큰이 들어 있다)는 [showLinks] 일 때만 로그에 남는다:
 * `skeleton.workspace.mail.log-links` 가 AUTO 이면 보호 환경(stage · prod)이 아닐 때만. 보호 환경에서 이 길이면 DeployGuard 가 막는다.
 */
class LogOnlyWorkspaceMailTransport(private val showLinks: Boolean) : WorkspaceMailTransport {
    private val log = LoggerFactory.getLogger("dev.sumin.skeleton.workspace.mail")

    override fun send(to: String, subject: String, text: String): Boolean {
        if (showLinks) log.info("MAIL (not sent: no mail transport) to={} subject='{}'\n{}", LogMasker.maskEmail(to), subject, text)
        else log.info("MAIL (not sent: no mail transport) to={} subject='{}' — set skeleton.workspace.mail.log-links=ON in local to see the link, or add modules:notification-mail", LogMasker.maskEmail(to), subject)
        return false
    }
}

/** 내장 틀 ko · en — 초대하는 사람의 요청 언어를 따르고, ko · en 이 아니면 [defaultLocale] */
class DefaultWorkspaceInvitationMail(private val defaultLocale: String = "en") : WorkspaceInvitationMail {
    override fun render(mail: InvitationMail, link: String): RenderedMail {
        val until = UNTIL.format(mail.expiresAt.atZone(ZoneOffset.UTC))
        return if (pick(mail.locale) == "ko") {
            val role = if (mail.role == WorkspaceRole.ADMIN) "관리자" else "멤버"
            val who = mail.inviterLabel?.let { "${it}님이" } ?: "누군가"
            RenderedMail(
                "${mail.workspaceName} 워크스페이스에 초대받았어요",
                "$who ${mail.workspaceName} 워크스페이스에 $role(으)로 초대했어요.\n\n" +
                    "아래 링크에서 수락하세요. 이 메일을 받은 주소로 확인된 계정으로 로그인해야 해요.\n$link\n\n" +
                    "이 링크는 $until 까지 쓸 수 있어요. 모르는 초대라면 이 메일을 무시하세요.",
            )
        } else {
            val role = if (mail.role == WorkspaceRole.ADMIN) "an admin" else "a member"
            val who = mail.inviterLabel ?: "Someone"
            RenderedMail(
                "You are invited to ${mail.workspaceName}",
                "$who invited you to join the ${mail.workspaceName} workspace as $role.\n\n" +
                    "Accept the invitation here. You need to sign in with an account whose email address is verified and matches the address this mail was sent to.\n$link\n\n" +
                    "This link works until $until. If you did not expect this invitation, ignore this email.",
            )
        }
    }

    private fun pick(locale: String?): String {
        val tag = locale?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
        return if (tag == "ko" || tag == "en") tag else if (defaultLocale.lowercase(Locale.ROOT).startsWith("ko")) "ko" else "en"
    }

    private companion object {
        val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
    }
}
