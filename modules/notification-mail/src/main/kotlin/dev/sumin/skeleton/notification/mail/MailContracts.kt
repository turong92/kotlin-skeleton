package dev.sumin.skeleton.notification.mail

/** 보낼 메일. html 이 있으면 multipart(text 대체 + html), 없으면 text 만. */
data class MailMessage(
    val to: List<String>,
    val subject: String,
    val text: String,
    val html: String? = null,
    val replyTo: String? = null,
) {
    init {
        require(to.isNotEmpty()) { "Mail recipients must not be empty." }
        require(subject.isNotBlank()) { "Mail subject must not be blank." }
        // 헤더에 들어가는 값에 줄바꿈이 있으면 헤더 주입이다 (Bcc: 를 끼워 넣는다) — 값을 고치지 않고 거부한다
        require(to.none(::hasLineBreak) && !hasLineBreak(subject) && replyTo?.let(::hasLineBreak) != true) { "Mail recipients, subject and reply-to must not contain line breaks." }
    }
}

data class MailSendResult(val accepted: Boolean, val detail: String? = null)

/** 앱이 쓰는 인터페이스. 기본 구현은 SMTP([SmtpMailSender]); 테스트에서는 이걸 가짜로 바꾼다. */
fun interface MailSender {
    fun send(message: MailMessage): MailSendResult
}

private fun hasLineBreak(value: String) = value.any { it == '\r' || it == '\n' }
