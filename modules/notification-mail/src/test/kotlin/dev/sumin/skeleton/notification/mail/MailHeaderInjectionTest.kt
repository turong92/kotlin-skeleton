package dev.sumin.skeleton.notification.mail

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** CR/LF 가 들어간 주소 · 제목 · 답장 주소는 헤더 주입이 되므로 메시지를 만들 때 거부한다 */
class MailHeaderInjectionTest {
    @Test
    fun `line breaks in recipients, subject or reply-to are rejected`() {
        assertFailsWith<IllegalArgumentException> { MailMessage(listOf("a@b.co\r\nBcc: x@y.z"), "s", "t") }
        assertFailsWith<IllegalArgumentException> { MailMessage(listOf("a@b.co"), "s\nBcc: x@y.z", "t") }
        assertFailsWith<IllegalArgumentException> { MailMessage(listOf("a@b.co"), "s", "t", replyTo = "a@b.co\rBcc: x") }
    }
}
