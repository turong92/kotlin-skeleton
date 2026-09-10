package dev.sumin.skeleton.notification.mail

import jakarta.mail.internet.MimeMessage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl

class SmtpMailSenderTest {
    private class RecordingJavaMailSender(private val fail: Boolean = false) : JavaMailSenderImpl() {
        val sent = mutableListOf<MimeMessage>()
        override fun doSend(mimeMessages: Array<out MimeMessage>, originalMessages: Array<out Any>?) {
            if (fail) throw IllegalStateException("smtp down")
            sent += mimeMessages
        }
    }

    @Test
    fun `from, to, subject, 본문(text+html) 을 MIME 으로 만들어 보낸다`() {
        val smtp = RecordingJavaMailSender()
        val sender = SmtpMailSender(smtp, "Ovation <no-reply@example.com>")

        val result = sender.send(MailMessage(to = listOf("fan@example.com"), subject = "편집 링크", text = "링크: x", html = "<a href='x'>링크</a>"))

        assertTrue(result.accepted)
        val mime = smtp.sent.single().also { it.saveChanges() } // 실제 전송 경로에서 Transport 가 호출하는 단계
        assertEquals("편집 링크", mime.subject)
        assertEquals("fan@example.com", mime.allRecipients.single().toString())
        assertTrue(mime.from.single().toString().contains("no-reply@example.com"))
        assertTrue(mime.contentType.startsWith("multipart/"))
    }

    @Test
    fun `전송 실패는 예외 대신 accepted=false 로 돌려준다`() {
        val sender = SmtpMailSender(RecordingJavaMailSender(fail = true), "no-reply@example.com")
        val result = sender.send(MailMessage(to = listOf("a@b.c"), subject = "s", text = "t"))
        assertFalse(result.accepted)
        assertEquals("smtp down", result.detail)
    }
}
