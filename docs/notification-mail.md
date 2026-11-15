# notification-mail — SMTP sending

```yaml
spring:
  mail:
    host: smtp.example.com
    port: 587
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
skeleton:
  notification-mail:
    enabled: true                      # default false: no host/secret -> module simply absent
    from: "Acme <no-reply@example.com>"
```

```kotlin
class EditLinkMailer(private val mail: MailSender) {
    fun send(to: String, link: String) =
        mail.send(MailMessage(to = listOf(to), subject = "편집 링크", text = "링크: $link", html = "<a href=\"$link\">편집 링크</a>"))
}
```

**광고 종류의 메일**(`skeleton.notification.advertising.types` 에 있는 `notificationType` · `accountId` 를 단 메일)은 `notification` 모듈이 있을 때 광고 게이트를 지난다 - 동의 없음 → 안 보냄, 야간 → `heldUntil` 로 미룸을 알림, 보내는 것은 `(광고)` · 수신 거부 문구(`body-suffix`)가 붙는다:

```kotlin
mail.send(MailMessage(to = listOf(email), subject = "새 기능", text = "...", notificationType = "promo.tips", accountId = account.id))
// accepted=false + heldUntil != null  → 그 시각 이후에 다시 보낸다 / accepted=false + heldUntil == null → 보내지 않는다(동의 없음)
```

`MailSendResult(accepted=false, detail)` instead of exceptions on transport errors — combine with
`job-queue-jdbc` for retries. Secrets and bodies are never logged.
