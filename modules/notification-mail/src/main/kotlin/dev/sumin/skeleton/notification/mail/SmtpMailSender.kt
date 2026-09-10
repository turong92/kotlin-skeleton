package dev.sumin.skeleton.notification.mail

import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper

class SmtpMailSender(
    private val javaMailSender: JavaMailSender,
    private val from: String,
) : MailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: MailMessage): MailSendResult {
        val mime = javaMailSender.createMimeMessage()
        val helper = MimeMessageHelper(mime, message.html != null, "UTF-8")
        helper.setFrom(from)
        helper.setTo(message.to.toTypedArray())
        helper.setSubject(message.subject)
        message.replyTo?.let(helper::setReplyTo)
        if (message.html != null) helper.setText(message.text, message.html) else helper.setText(message.text, false)
        return try {
            javaMailSender.send(mime)
            MailSendResult(accepted = true)
        } catch (ex: Exception) {
            // 비밀번호·본문은 로그에 남기지 않는다 (수신자 수와 제목만)
            log.warn("Mail send failed: recipients={} subject='{}' error={}", message.to.size, message.subject, ex.message)
            MailSendResult(accepted = false, detail = ex.message)
        }
    }
}
