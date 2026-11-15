package dev.sumin.skeleton.notification.mail

/** 보낼 메일. html 이 있으면 multipart(text 대체 + html), 없으면 text 만. */
data class MailMessage(
    val to: List<String>,
    val subject: String,
    val text: String,
    val html: String? = null,
    val replyTo: String? = null,
    /** 알림 종류 - `skeleton.notification.advertising.types` 안의 종류면 이 메일은 광고성이다: 동의 · 야간 · `(광고)` · 수신 거부 문구 게이트를 거친다([AdvertisingMailSender]). null 이면 정보성 */
    val notificationType: String? = null,
    /** 받는 사람의 계정 id - 광고성 메일은 한 계정에 한 통이어야 하고(동의는 계정마다 본다) 이 값이 필수다 */
    val accountId: String? = null,
) {
    init {
        require(to.isNotEmpty()) { "Mail recipients must not be empty." }
        require(subject.isNotBlank()) { "Mail subject must not be blank." }
        // 헤더에 들어가는 값에 줄바꿈이 있으면 헤더 주입이다 (Bcc: 를 끼워 넣는다) — 값을 고치지 않고 거부한다
        require(to.none(::hasLineBreak) && !hasLineBreak(subject) && replyTo?.let(::hasLineBreak) != true) { "Mail recipients, subject and reply-to must not contain line breaks." }
    }
}

/** [heldUntil] 이 있으면 안 보낸 게 아니라 **미룬 것**이다(야간 광고) - 보내는 쪽이 그 시각 이후에 다시 보낸다. 메일 보내기는 큐가 없어 미루기를 대신하지 못한다 */
data class MailSendResult(val accepted: Boolean, val detail: String? = null, val heldUntil: java.time.Instant? = null)

/** 앱이 쓰는 인터페이스. 기본 구현은 SMTP([SmtpMailSender]); 테스트에서는 이걸 가짜로 바꾼다. */
fun interface MailSender {
    fun send(message: MailMessage): MailSendResult
}

private fun hasLineBreak(value: String) = value.any { it == '\r' || it == '\n' }
