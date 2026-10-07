package dev.sumin.skeleton.account.mail

import dev.sumin.skeleton.account.AccountProperties
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.slf4j.LoggerFactory

enum class MailKind {
    /** 6자리 코드를 담은 메일 — 가입 확인 · 이메일 변경(새 주소) · 다시 인증 · 삭제 확인. 링크가 없다 */
    VERIFY_CODE, EMAIL_CHANGE_CODE, REAUTH_CODE, DELETE_CODE,
    ALREADY_REGISTERED, PASSWORD_RESET, PASSWORD_CHANGED,
    EMAIL_CHANGE_REQUESTED_NOTICE, EMAIL_CHANGED_NOTICE, MAGIC_LINK, DELETION_SCHEDULED, DELETION_CANCELLED, IDENTITY_LINKED_NOTICE,
}

/** 보낼 메일 한 통의 의미 — 문구는 [AccountMailTemplates] 가 고른다. [link] 에 토큰이 들어 있다 (이 객체를 로그에 싣지 않는다 — toString 은 가려 둔다) */
data class AccountMail(
    val kind: MailKind,
    val to: String,
    val locale: String?,
    val link: String? = null,
    val vars: Map<String, String> = emptyMap(),
) {
    override fun toString() = "AccountMail(kind=$kind, to=<redacted>, locale=$locale, link=${if (link == null) "none" else "<redacted>"})"
}

data class RenderedMail(val subject: String, val text: String, val html: String?)

/** 앱이 문구를 바꾸려면 이 빈을 두면 된다 (기본: ko · en 내장) */
interface AccountMailTemplates {
    fun render(kind: MailKind, locale: String?, vars: Map<String, String>, link: String?): RenderedMail
}

/** 실제로 보내는 길 — `notification-mail` 이 있으면 그 `MailSender` 로, 없으면 로그만([LogOnlyMailTransport]) */
fun interface AccountMailTransport {
    /** 받아들여졌으면 true */
    fun send(to: String, subject: String, text: String, html: String?): Boolean
}

/** 계정 모듈이 메일을 보내는 한 곳 */
fun interface AccountMailer {
    fun send(mail: AccountMail)
}

/** 링크로 가는 메일은 비밀번호 재설정 · 매직 링크 둘뿐이다 — 세션이 없는 흐름이라서. 나머지는 코드다 */
class AccountLinks(private val props: AccountProperties.Mail) {
    fun reset(token: String) = build(props.resetPath, token)
    fun magicLink(token: String) = build(props.magicLinkPath, token)

    /** 프론트의 로그인 페이지 — 토큰 없는 주소. 프론트 주소(`link-base-url`)가 비면 null (상대 주소는 메일에서 쓸모가 없다) */
    fun login(): String? = props.linkBaseUrl.takeIf { it.isNotBlank() }?.let { it.trimEnd('/') + props.loginPath }

    private fun build(path: String, token: String): String =
        props.linkBaseUrl.trimEnd('/') + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
}

class TemplatedAccountMailer(
    private val templates: AccountMailTemplates,
    private val transport: AccountMailTransport,
    private val props: AccountProperties.Mail,
) : AccountMailer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(mail: AccountMail) {
        val rendered = templates.render(mail.kind, mail.locale, mail.vars, mail.link)
        // 제목은 한 줄이다 — 접두사 · 템플릿이 줄바꿈을 끼워도 헤더가 되지 못한다
        val subject = (if (props.subjectPrefix.isBlank()) rendered.subject else "${props.subjectPrefix} ${rendered.subject}").replace(Regex("[\\r\\n]+"), " ")
        val html = if (props.htmlEnabled) rendered.html else null
        val accepted = try { transport.send(mail.to, subject, rendered.text, html) } catch (e: Exception) {
            log.warn("account mail kind={} failed: {}", mail.kind, e.javaClass.simpleName); false
        }
        if (!accepted) log.warn("account mail kind={} was not accepted by the transport", mail.kind)
    }
}

/**
 * 메일 모듈이 없을 때의 길 — 보내지 않고 알린다. 링크(토큰이 들어 있다)는 [showLinks] 일 때만 로그에 남는다:
 * 기본(OFF)에서는 남기지 않고, 로컬에서 앱이 `skeleton.account.mail.log-links` 를 켜 두었을 때만 (보호 환경에서 켜면 DeployGuard 문제).
 */
class LogOnlyMailTransport(private val showLinks: Boolean) : AccountMailTransport {
    private val log = LoggerFactory.getLogger("dev.sumin.skeleton.account.mail")

    override fun send(to: String, subject: String, text: String, html: String?): Boolean {
        if (showLinks) log.info("MAIL (not sent: no mail transport) to={} subject='{}'\n{}", maskTo(to), subject, text)
        else log.info("MAIL (not sent: no mail transport) to={} subject='{}' — set skeleton.account.mail.log-links=true in local to see the link, or add modules:notification-mail", maskTo(to), subject)
        return false
    }

    private fun maskTo(to: String) = dev.sumin.skeleton.common.logging.LogMasker.maskEmail(to)
}

internal fun pickLocale(locale: String?, default: String): String {
    val tag = locale?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
    return if (tag == "ko" || tag == "en") tag else if (default.lowercase(Locale.ROOT).startsWith("ko")) "ko" else "en"
}
