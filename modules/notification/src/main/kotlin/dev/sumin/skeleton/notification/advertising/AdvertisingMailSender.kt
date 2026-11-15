package dev.sumin.skeleton.notification.advertising

import dev.sumin.skeleton.notification.mail.MailMessage
import dev.sumin.skeleton.notification.mail.MailSendResult
import dev.sumin.skeleton.notification.mail.MailSender
import org.slf4j.LoggerFactory

/**
 * 광고 종류([MailMessage.notificationType] 이 광고 종류)로 표시된 메일을 광고 게이트([AdvertisingGate])에 태운다 - 푸시와 같은 규칙이다.
 * 동의가 없으면 안 보내고(`accepted=false`), 야간이고 야간 동의가 없으면 미룬다(`accepted=false` + [MailSendResult.heldUntil]), 보내는 것은 제목에 `(광고)` 를 · 본문 끝(text · html)에 수신 거부 문구를 단다.
 * 한 계정에 한 통이어야 한다 - [MailMessage.accountId] 가 없거나 받는 사람이 여럿이면 동의를 볼 수 없어 **거절**한다. 광고 종류가 아닌 메일은 그대로 지나간다.
 * [gate] 는 보낼 때 읽는다(빈 순환을 피한다).
 */
class AdvertisingMailSender(
    private val delegate: MailSender,
    private val gate: () -> AdvertisingGate,
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
        return when (val decision = gate.decide(account)) {
            AdvertisingDecision.Skip -> MailSendResult(accepted = false, detail = "no valid advertising consent")
            is AdvertisingDecision.Hold -> MailSendResult(accepted = false, detail = "held until the night ends", heldUntil = decision.runAt)
            AdvertisingDecision.SendNow -> delegate.send(brand(gate, message))
        }
    }

    private fun brand(gate: AdvertisingGate, message: MailMessage): MailMessage {
        val branded = gate.brand(message.subject, message.text, Int.MAX_VALUE)
        val suffix = branded.body.orEmpty().removePrefix(message.text).trim()
        val html = message.html?.let { if (suffix.isEmpty() || it.contains(escape(suffix))) it else "$it\n<p>${escape(suffix)}</p>" }
        return message.copy(subject = branded.title, text = branded.body.orEmpty(), html = html)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
