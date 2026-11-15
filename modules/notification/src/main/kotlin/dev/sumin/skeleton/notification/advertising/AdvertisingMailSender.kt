package dev.sumin.skeleton.notification.advertising

import dev.sumin.skeleton.notification.mail.MailMessage
import dev.sumin.skeleton.notification.mail.MailSendResult
import dev.sumin.skeleton.notification.mail.MailSender
import org.slf4j.LoggerFactory

/**
 * 광고 종류([MailMessage.notificationType] 이 광고 종류)로 표시된 메일을 광고 게이트([AdvertisingGate])에 태운다 - 푸시와 같은 규칙이되 **야간 규칙은 메일에 없다**:
 * 정보통신망법 제50조 제3항 단서의 「대통령령으로 정하는 매체」가 전자우편이다(시행령 제61조 제2항) - 야간(21~08시)에도 광고 수신 동의만 있으면 보낸다(야간 별도 동의 불필요).
 * 동의가 없으면 안 보내고(`accepted=false`), 보내는 것은 제목에 `(광고)` · 본문 끝(text · html)에 전송자 명칭([AdvertisingProperties.senderName]) · 수신 거부 문구를 단다.
 * 한 계정에 한 통이어야 한다 - [MailMessage.accountId] 가 없거나 받는 사람이 여럿이면 동의를 볼 수 없어 **거절**한다. 광고 종류가 아닌 메일은 그대로 지나간다.
 * [gate] · [properties] 는 보낼 때 읽는다(빈 순환을 피한다).
 */
class AdvertisingMailSender(
    private val delegate: MailSender,
    private val gate: () -> AdvertisingGate,
    private val properties: () -> AdvertisingProperties,
) : MailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: MailMessage): MailSendResult {
        val type = message.notificationType
        val gate = gate()
        if (type == null || !gate.isAdvertising(type)) return delegate.send(message)
        val account = message.accountId
        if (account.isNullOrBlank() || message.to.size != 1) {
            log.warn("advertising mail refused: it needs accountId and exactly one recipient type={}", type)
            return MailSendResult(accepted = false, detail = "advertising mail needs accountId and exactly one recipient")
        }
        return when (gate.decide(account)) {
            AdvertisingDecision.Skip -> MailSendResult(accepted = false, detail = "no valid advertising consent")
            // Hold = 야간 + 야간 동의 없음 - 메일은 야간 규칙 밖이라 광고 동의(Skip 이 아님)만 본다
            AdvertisingDecision.SendNow, is AdvertisingDecision.Hold -> delegate.send(brand(gate, properties(), message))
        }
    }

    private fun brand(gate: AdvertisingGate, properties: AdvertisingProperties, message: MailMessage): MailMessage {
        val suffix = properties.bodySuffix.trim()
        val sender = properties.senderName?.trim()?.takeIf { it.isNotEmpty() }?.let { "전송자: $it" }
        val core = (if (suffix.isNotEmpty()) message.text.removeSuffix(suffix) else message.text).trimEnd()
        val withSender = if (sender != null && sender !in core) "$core\n\n$sender" else core
        val branded = gate.brand(message.subject, withSender, Int.MAX_VALUE)
        val footer = listOfNotNull(sender?.takeIf { message.html?.contains(escape(it)) != true }, suffix.takeIf { it.isNotEmpty() && message.html?.contains(escape(it)) != true })
        val html = message.html?.let { insertBeforeBodyEnd(it, footer.joinToString("\n") { "<p>${escape(it)}</p>" }) }
        return message.copy(subject = branded.title, text = branded.body.orEmpty(), html = html)
    }

    private fun insertBeforeBodyEnd(html: String, block: String): String {
        if (block.isEmpty()) return html
        val at = html.lastIndexOf("</body>", ignoreCase = true)
        return if (at < 0) "$html\n$block" else html.substring(0, at) + block + "\n" + html.substring(at)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
