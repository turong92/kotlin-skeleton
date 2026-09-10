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
    from: "Ovation <no-reply@example.com>"
```

```kotlin
class EditLinkMailer(private val mail: MailSender) {
    fun send(to: String, link: String) =
        mail.send(MailMessage(to = listOf(to), subject = "편집 링크", text = "링크: $link", html = "<a href=\"$link\">편집 링크</a>"))
}
```

`MailSendResult(accepted=false, detail)` instead of exceptions on transport errors — combine with
`job-queue-jdbc` for retries. Secrets and bodies are never logged.
