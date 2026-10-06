package dev.sumin.skeleton.notification.mail

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl

/**
 * 진짜 SMTP 왕복 — 진짜 `JavaMailSenderImpl` 이 mailpit 에 보내고, mailpit 의 API 로 받은 바이트를 본다.
 * mailpit 이 없으면 건너뛴다. 띄우기: `MAIL_SMTP_PORT=21025 MAIL_HTTP_PORT=28025 docker compose -p mailtest --profile mail up -d mail`
 * (같은 두 환경변수로 이 시험을 돌린다. 기본 1025 · 8025 — `scripts/sample-e2e-backend.sh` 와 같은 수신기). 끝나면 `docker compose -p mailtest --profile mail down`.
 */
class SmtpMailSenderMailpitTest {
    private val smtpPort = (System.getenv("MAIL_SMTP_PORT") ?: "1025").toInt()
    private val httpPort = (System.getenv("MAIL_HTTP_PORT") ?: "8025").toInt()
    private val http = HttpClient.newHttpClient()

    private fun get(path: String): String? = try {
        http.send(HttpRequest.newBuilder(URI("http://localhost:$httpPort$path")).build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).takeIf { it.statusCode() == 200 }?.body()
    } catch (e: Exception) { null }

    @Test
    fun `a Korean multipart mail arrives with both parts, an encoded UTF-8 subject and the From name`() {
        assumeTrue(get("/api/v1/info") != null, "mailpit is not running on $httpPort (see the class comment)")
        val smtp = JavaMailSenderImpl().apply { host = "localhost"; port = smtpPort; defaultEncoding = "UTF-8" }
        val marker = "rt-${System.nanoTime()}"
        val result = SmtpMailSender(smtp, "노트 서비스 <no-reply@notes.example>").send(
            MailMessage(to = listOf("fan-$marker@example.com"), subject = "[노트] 인증번호를 보내 드려요", text = "인증번호는 739518 예요. 한글 본문", html = "<p>인증번호 <b>739518</b> 한글 본문</p>"),
        )
        assertTrue(result.accepted, result.detail)

        val list = get("/api/v1/search?query=" + java.net.URLEncoder.encode("to:fan-$marker@example.com", Charsets.UTF_8)) ?: error("mailpit search failed")
        val id = Regex("\"ID\"\\s*:\\s*\"([^\"]+)\"").find(list)?.groupValues?.get(1) ?: error("the mail did not arrive: $list")
        val json = get("/api/v1/message/$id") ?: error("message fetch failed")
        assertContains(json, "[노트] 인증번호를 보내 드려요")
        assertContains(json, "\"Name\":\"노트 서비스\"")
        assertContains(json, "no-reply@notes.example")
        assertContains(json, "인증번호는 739518 예요. 한글 본문")           // text part
        assertContains(json, "\\u003cb\\u003e739518\\u003c/b\\u003e")                                // html part

        val raw = get("/api/v1/message/$id/raw") ?: error("raw fetch failed")
        assertContains(raw, "multipart/alternative")
        assertTrue(Regex("(?i)content-type: text/plain;\\s*charset=utf-8").containsMatchIn(raw))
        assertTrue(Regex("(?i)content-type: text/html;\\s*charset=utf-8").containsMatchIn(raw))
        assertContains(raw, "Subject: =?UTF-8?")                             // the Korean subject is RFC 2047 encoded, not raw 8-bit
        assertTrue(raw.lineSequence().takeWhile { it.isNotEmpty() }.none { l -> l.any { it.code > 127 } }, "no raw 8-bit byte in the header block")
    }
}
